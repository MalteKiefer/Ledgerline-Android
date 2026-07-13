package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.model.GalleryRoot
import de.ledgerline.app.domain.model.Session
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryRepository(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val cache: GalleryCache,
    private val storeCache: StoreDiskCache,
    private val offlineFlags: OfflineFlags,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        cache: GalleryCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
    ) : this(
        sessionHolder,
        vaultKeyHolder,
        crypto,
        cache,
        storeCache,
        offlineFlags,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    private companion object {
        const val KEY = "gallery"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonEncoder = Json { encodeDefaults = true }

    /** Gallery blob storage usage: (used bytes, quota bytes). Null on any failure. */
    suspend fun galleryUsage(): Pair<Long, Long>? {
        val session = sessionHolder.get() ?: return null
        return try {
            val res = apiProvider(session).galleryUsage()
            if (!res.isSuccessful) return null
            val body = res.body() ?: return null
            body.used to body.quota
        } catch (_: Exception) {
            null
        }
    }

    suspend fun load(): Outcome<Gallery> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        return try {
            val res = apiProvider(session).galleryStore()
            when {
                // 401 stays an auth failure → forced-logout path; never fall back to cache.
                res.code() == HttpURLConnection.HTTP_UNAUTHORIZED -> Outcome.Err(ErrorKind.HTTP)
                !res.isSuccessful -> cachedOr(Outcome.Err(ErrorKind.NETWORK), vk)
                else -> {
                    val body = res.body()!!
                    val manifest = body.ciphertext?.let { ct ->
                        val plain = crypto.openManifest(ct, vk) ?: return Outcome.Err(ErrorKind.DECRYPT)
                        assembleManifest(json.decodeFromString<GalleryRoot>(plain), session, vk)
                    } ?: GalleryManifest()
                    if (offlineFlags.enabled()) {
                        storeCache.put(KEY, StoreEnvelope(body.ciphertext, body.version))
                    }
                    Outcome.Ok(Gallery(manifest, body.version))
                }
            }
        } catch (e: Exception) { cachedOr(Outcome.Err(ErrorKind.NETWORK, e), vk) }
    }

    /**
     * Build the in-memory [GalleryManifest] from the raw root. v2 fetches + decrypts each
     * photo shard blob and concatenates the records; v1 uses the inline photos. A failing
     * shard fetch THROWS (fails the whole load, mirroring the web client) rather than
     * silently dropping photos — losing photos here would let a reconcile free their blobs.
     * The assembled manifest is v1-shaped, so a later save writes inline photos (web reads
     * that as legacy and re-shards on its next save).
     */
    private suspend fun assembleManifest(root: GalleryRoot, session: Session, vk: ByteArray): GalleryManifest {
        val photos = if (root.v >= 2 && root.shards.isNotEmpty()) {
            val api = apiProvider(session)
            root.shards.flatMap { s ->
                val r = api.galleryRaw(s.ref)
                check(r.isSuccessful) { "gallery shard ${s.ref}: http ${r.code()}" }
                val bytes = BlobDownloader.decrypt(r.body()!!.bytes(), s.key, vk, crypto)
                json.decodeFromString<List<GalleryPhoto>>(bytes.decodeToString())
            }
        } else {
            root.photos
        }
        return GalleryManifest(photos = photos, albums = root.albums, people = root.people)
    }

    /**
     * Network-first cache fallback: when a fetch fails with a non-auth error, try the
     * on-disk sealed envelope and decrypt it in-memory with [vk]. Returns [err]
     * unchanged if offline caching is off, no entry exists, or decryption fails.
     */
    private fun cachedOr(err: Outcome<Gallery>, vk: ByteArray): Outcome<Gallery> =
        cachedOrStore(
            cachingEnabled = offlineFlags.enabled(),
            envelope = storeCache.get(KEY),
            err = err,
            open = { ct -> crypto.openManifest(ct, vk) },
            decode = { plain -> json.decodeFromString<GalleryManifest>(plain) },
            empty = { GalleryManifest() },
            wrap = { m, v -> Gallery(m, v) },
        )

    /**
     * Optimistic write: apply [mutate] to the current manifest, PUT it; on 409 reload
     * the server manifest, re-apply [mutate], and retry (bounded to 4 attempts).
     * Updates the cache on success.
     */
    suspend fun save(mutate: (GalleryManifest) -> GalleryManifest): Outcome<Gallery> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)
        val current = cache.value.value

        return optimisticSave(
            cached = current?.let { it.manifest to it.version },
            mutate = mutate,
            fetch = { api.galleryStore() },
            put = { api.galleryStorePut(it) },
            seal = { m -> crypto.sealManifest(jsonEncoder.encodeToString(GalleryManifest.serializer(), m), vk) },
            open = { ct -> crypto.openManifest(ct, vk) },
            decode = { plain -> json.decodeFromString<GalleryManifest>(plain) },
            empty = { GalleryManifest() },
            wrap = { m, v -> Gallery(m, v) },
            onSaved = { cache.set(it) },
            onEnvelope = { env -> if (offlineFlags.enabled()) storeCache.put(KEY, env) },
        )
    }
}
