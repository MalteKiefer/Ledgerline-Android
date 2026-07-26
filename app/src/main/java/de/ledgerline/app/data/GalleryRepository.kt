package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.CanonicalJson
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.domain.model.GalleryAlbum
import de.ledgerline.app.domain.model.GalleryPerson
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
    private val galleryUpload: de.ledgerline.app.domain.usecase.GalleryUploadApi,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        cache: GalleryCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
        galleryUpload: de.ledgerline.app.domain.usecase.GalleryUploadApi,
    ) : this(
        sessionHolder,
        vaultKeyHolder,
        crypto,
        cache,
        storeCache,
        offlineFlags,
        galleryUpload,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    private companion object {
        const val KEY = "gallery"
    }

    // coerceInputValues: the sharded gallery root writes `"photos": null` alongside the
    // shard list; coercing a JSON null on a non-null defaulted field (photos/albums/people)
    // to its default keeps decode robust instead of throwing JsonDecodingException.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val jsonEncoder = Json { encodeDefaults = true }

    // The original raw JsonObject of each record from the last load, keyed by id, so a save
    // re-emits every web field byte-exact (no data loss) via [GalleryRecordCodec] — see it.
    private val photoRawById = java.util.concurrent.ConcurrentHashMap<String, kotlinx.serialization.json.JsonObject>()
    private val albumRawById = java.util.concurrent.ConcurrentHashMap<String, kotlinx.serialization.json.JsonObject>()
    private val personRawById = java.util.concurrent.ConcurrentHashMap<String, kotlinx.serialization.json.JsonObject>()

    // Store-v3 sharded write engine. Records are encoded web-compatibly by the codec
    // (raw-overlay); uploadBlob encrypts a content blob + Padmé-pads + uploads.
    private val shardWriter = GalleryShardWriter(
        encodePhoto = { p -> GalleryRecordCodec.encodePhoto(p, photoRawById[p.id]) },
        encodeAlbum = { a -> GalleryRecordCodec.encodeAlbum(a, albumRawById[a.id]) },
        encodePerson = { p -> GalleryRecordCodec.encodePerson(p, personRawById[p.id]) },
        uploadBlob = { bytes, name -> (galleryUpload.uploadBytes(bytes, name) as? Outcome.Ok)?.value },
    )

    // The last sealed-root state, for dirty-save reuse across consecutive writes. Rebased
    // on 409 from the winning writer's root.
    @Volatile
    private var priorRoot: GalleryShardWriter.RootState = GalleryShardWriter.RootState()

    /** The dirty-save reuse state carried by a decoded [GalleryRoot]. */
    private fun rootStateFrom(root: GalleryRoot) = GalleryShardWriter.RootState(
        shardBits = root.shardBits,
        shards = root.shards,
        albums = root.albumsRef?.let { GalleryShardWriter.CollDesc(it, root.albumsKey ?: "", root.albumsHash ?: "") },
        people = root.peopleRef?.let { GalleryShardWriter.CollDesc(it, root.peopleKey ?: "", root.peopleHash ?: "") },
    )

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

    // Runs on Dispatchers.IO: the sharded gallery pulls + decrypts + JSON-decodes thousands
    // of photo records (seconds of CPU), which must never touch the main thread (the caller
    // launches on viewModelScope = Main) — otherwise the load ANRs.
    suspend fun load(): Outcome<Gallery> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        try {
            val res = apiProvider(session).galleryStore()
            when {
                // 401 stays an auth failure → forced-logout path; never fall back to cache.
                res.code() == HttpURLConnection.HTTP_UNAUTHORIZED -> Outcome.Err(ErrorKind.HTTP)
                !res.isSuccessful -> cachedOr(Outcome.Err(ErrorKind.NETWORK), vk)
                else -> {
                    val body = res.body()!!
                    val manifest = body.ciphertext?.let { ct ->
                        val plain = crypto.openManifest(ct, vk) ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
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
        val api = apiProvider(session)
        // Remember the sealed-root state + the raw records so the next save reuses unchanged
        // blobs and re-emits every web field byte-exact (no data loss).
        priorRoot = rootStateFrom(root)
        photoRawById.clear(); albumRawById.clear(); personRawById.clear()

        val photos = if (root.v >= 2 && root.shards.isNotEmpty()) {
            // Fetch + decrypt + decode all shards concurrently; a failing shard throws
            // (mirrors the web client — never silently drop photos, which a reconcile could free).
            coroutineScope {
                root.shards.map { s ->
                    async(Dispatchers.IO) {
                        val r = api.galleryRaw(s.ref)
                        check(r.isSuccessful) { "gallery shard ${s.ref}: http ${r.code()}" }
                        val bytes = BlobDownloader.decrypt(r.body()!!.bytes(), s.key, vk, crypto)
                        json.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject }
                    }
                }.awaitAll().flatten()
            }.map { obj ->
                val p = GalleryRecordCodec.decodePhoto(obj)
                photoRawById[p.id] = obj
                p
            }
        } else {
            root.photos
        }

        // v3 keeps albums/people in content-addressed collection blobs (refs on the root);
        // v1/v2 inline them. Prefer the blob when the ref is present.
        val albums = if (root.albumsRef != null) {
            fetchCollectionRaw(api, root.albumsRef!!, root.albumsKey ?: "", vk).map { obj ->
                GalleryRecordCodec.decodeAlbum(obj).also { albumRawById[it.id] = obj }
            }
        } else {
            root.albums
        }
        val people = if (root.peopleRef != null) {
            fetchCollectionRaw(api, root.peopleRef!!, root.peopleKey ?: "", vk).map { obj ->
                GalleryRecordCodec.decodePerson(obj).also { personRawById[it.id] = obj }
            }
        } else {
            root.people
        }
        return GalleryManifest(photos = photos, albums = albums, people = people)
    }

    /** Download + decrypt a collection blob (albums/people) into its raw record objects. */
    private suspend fun fetchCollectionRaw(
        api: LedgerlineApi,
        ref: String,
        key: String,
        vk: ByteArray,
    ): List<kotlinx.serialization.json.JsonObject> {
        val r = api.galleryRaw(ref)
        check(r.isSuccessful) { "gallery collection $ref: http ${r.code()}" }
        val bytes = BlobDownloader.decrypt(r.body()!!.bytes(), key, vk, crypto)
        return json.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject }
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
    /**
     * Store-v3 sharded write: apply [mutate], build the sharded root (photos → shard
     * blobs, albums/people → collection blobs, reusing unchanged blobs from [priorRoot]),
     * seal the root under VK, and PUT it with the `shards[]` referential guard. On 409,
     * rebase on the winning writer's root, re-apply [mutate], and retry (bounded).
     */
    suspend fun save(mutate: (GalleryManifest) -> GalleryManifest): Outcome<Gallery> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)

        var base = cache.value.value
        if (base == null) {
            when (val l = load()) {
                is Outcome.Ok -> base = l.value
                is Outcome.Err -> return@withContext l
            }
        }
        var version = base!!.version
        var next = mutate(base!!.manifest)

        repeat(5) {
            val result = shardWriter.build(next.photos, next.albums, next.people, priorRoot)
                ?: return@withContext Outcome.Err(ErrorKind.NETWORK) // a shard/collection upload failed
            val rootCipher = crypto.sealManifest(CanonicalJson.encode(result.rootJson), vk)
            val put = try {
                api.galleryStorePut(StorePutRequest(rootCipher, version, result.shardRefs))
            } catch (e: Exception) {
                return@withContext Outcome.Err(ErrorKind.NETWORK, e)
            }
            when {
                put.isSuccessful -> {
                    val nv = put.body()?.version ?: (version + 1)
                    priorRoot = result.state
                    val gallery = Gallery(next, nv)
                    cache.set(gallery)
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(rootCipher, nv))
                    return@withContext Outcome.Ok(gallery)
                }
                put.code() == 409 -> {
                    // Reload the winning root (also rebases priorRoot), re-apply mutate.
                    val res = try {
                        api.galleryStore()
                    } catch (e: Exception) {
                        return@withContext Outcome.Err(ErrorKind.NETWORK, e)
                    }
                    if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
                    val body = res.body()!!
                    version = body.version
                    val serverManifest = body.ciphertext?.let { ct ->
                        val plain = crypto.openManifest(ct, vk) ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
                        assembleManifest(json.decodeFromString<GalleryRoot>(plain), session, vk)
                    } ?: GalleryManifest().also { priorRoot = GalleryShardWriter.RootState() }
                    next = mutate(serverManifest)
                }
                else -> return@withContext Outcome.Err(ErrorKind.HTTP)
            }
        }
        Outcome.Err(ErrorKind.HTTP) // gave up after retries
    }
}
