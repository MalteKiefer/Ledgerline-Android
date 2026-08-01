package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.ExploreCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.offline.StoreEnvelope
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.ReconcileRequest
import kotlinx.serialization.json.contentOrNull
import de.ledgerline.app.domain.model.ExploreManifest
import de.ledgerline.app.domain.model.ExploreStore
import de.ledgerline.app.domain.model.ExploreTrackCodec
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads + writes the sealed `store/explore` module (GPS tracks). Same optimistic-concurrency
 * envelope as the other modules: 409 → reload + re-apply the mutation. Tracks are opaque to the
 * server; foreign top-level keys (couplings/settings) and foreign per-track keys survive via the
 * raw-JSON overlay in [ExploreTrackCodec].
 */
@Singleton
class ExploreRepository(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val cache: ExploreCache,
    private val storeCache: StoreDiskCache,
    private val offlineFlags: OfflineFlags,
    private val syncOutbox: de.ledgerline.app.core.offline.SyncOutbox,
    private val connectivity: de.ledgerline.app.core.offline.Connectivity,
    private val apiProvider: (Session) -> LedgerlineApi,
) : de.ledgerline.app.core.offline.SyncableStore {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        cache: ExploreCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
        syncOutbox: de.ledgerline.app.core.offline.SyncOutbox,
        connectivity: de.ledgerline.app.core.offline.Connectivity,
    ) : this(
        sessionHolder, vaultKeyHolder, crypto, cache, storeCache, offlineFlags, syncOutbox, connectivity,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    private companion object {
        const val KEY = "explore"
        const val MODULE = "explore"
        val LIST_KEYS = listOf("tracks")
    }

    override val syncLabel: String = "explore"

    // ---- Offline write outbox (record-level delta over the manifest root) ----
    private fun collectionsOf(m: ExploreManifest) =
        de.ledgerline.app.core.offline.ManifestDelta.collections(ExploreTrackCodec.encodeManifest(m), LIST_KEYS)

    private fun applyDelta(m: ExploreManifest, delta: de.ledgerline.app.core.offline.StoreDelta): ExploreManifest =
        ExploreTrackCodec.decodeManifest(
            de.ledgerline.app.core.offline.ManifestDelta.apply(ExploreTrackCodec.encodeManifest(m), delta, LIST_KEYS),
        )

    private fun withPending(m: ExploreManifest, vk: ByteArray): ExploreManifest =
        syncOutbox.pending(KEY, vk)?.let { applyDelta(m, it) } ?: m

    private fun enqueueOffline(vk: ByteArray, base: ExploreManifest, next: ExploreManifest): Outcome<ExploreStore> {
        val delta = de.ledgerline.app.core.offline.StoreDelta.diff(collectionsOf(base), collectionsOf(next))
        if (!delta.isEmpty) syncOutbox.append(KEY, delta, vk)
        val store = ExploreStore(next, cache.value.value?.version ?: 0)
        cache.set(store)
        return Outcome.Ok(store)
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonEncoder = Json { encodeDefaults = true; explicitNulls = false }

    val cached: ExploreCache get() = cache

    private fun decodeManifest(plain: String): ExploreManifest {
        val root = json.parseToJsonElement(plain) as? JsonObject ?: JsonObject(emptyMap())
        return ExploreTrackCodec.decodeManifest(root)
    }

    private fun sealManifest(m: ExploreManifest, vk: ByteArray): String {
        val root = ExploreTrackCodec.encodeManifest(m)
        return crypto.sealManifest(jsonEncoder.encodeToString(JsonObject.serializer(), root), vk)
    }

    suspend fun load(): Outcome<ExploreStore> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val api = apiProvider(session)
        if (cache.value.value == null && offlineFlags.enabled()) {
            (cachedOr(Outcome.Err(ErrorKind.NETWORK), vk) as? Outcome.Ok)?.let { cache.set(ExploreStore(withPending(it.value.manifest, vk), it.value.version)) }
        }
        try {
            val res = api.moduleStore(MODULE)
            when {
                res.code() == HttpURLConnection.HTTP_UNAUTHORIZED -> Outcome.Err(ErrorKind.HTTP)
                !res.isSuccessful -> cachedOr(Outcome.Err(ErrorKind.NETWORK), vk)
                else -> {
                    val body = res.body()!!
                    val manifest = body.ciphertext?.let { ct ->
                        val plain = crypto.openManifest(ct, vk) ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
                        decodeManifest(plain)
                    } ?: ExploreManifest()
                    if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(body.ciphertext, body.version))
                    val store = ExploreStore(withPending(manifest, vk), body.version)
                    cache.set(store)
                    // Reconcile explore raw blobs (living-set = every track's rawBlobId) so a deleted
                    // imported track's original file is freed (24h grace). Full online load only.
                    val rawIds = manifest.tracks.mapNotNull {
                        (it.raw["rawBlobId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    }.filter { it.isNotBlank() }
                    if (rawIds.isNotEmpty()) runCatching { api.exploreReconcile(ReconcileRequest(rawIds)) }
                    Outcome.Ok(store)
                }
            }
        } catch (e: Exception) {
            cachedOr(Outcome.Err(ErrorKind.NETWORK, e), vk)
        }
    }

    suspend fun save(mutate: (ExploreManifest) -> ExploreManifest): Outcome<ExploreStore> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val base = cache.value.value?.manifest
            ?: (load() as? Outcome.Ok)?.value?.manifest
            ?: ExploreManifest()
        if (!connectivity.isOnline()) return@withContext enqueueOffline(vk, base, mutate(base))
        val out = pushOptimistic(session, vk, cache.value.value?.let { it.manifest to it.version }, mutate)
        if (out is Outcome.Err && out.kind == ErrorKind.NETWORK) enqueueOffline(vk, base, mutate(base)) else out
    }

    /** The raw optimistic seal+PUT (no offline handling) — shared by [save] and [replayPending]. */
    private suspend fun pushOptimistic(
        session: Session,
        vk: ByteArray,
        cached: Pair<ExploreManifest, Int>?,
        mutate: (ExploreManifest) -> ExploreManifest,
    ): Outcome<ExploreStore> {
        val api = apiProvider(session)
        return optimisticSave(
            cached = cached,
            mutate = mutate,
            fetch = { api.moduleStore(MODULE) },
            put = { api.putModuleStore(MODULE, it) },
            seal = { m -> sealManifest(m, vk) },
            open = { ct -> crypto.openManifest(ct, vk) },
            decode = { plain -> decodeManifest(plain) },
            empty = { ExploreManifest() },
            wrap = { m, v -> ExploreStore(m, v) },
            onSaved = { cache.set(it) },
            onEnvelope = { env -> if (offlineFlags.enabled()) storeCache.put(KEY, env) },
        )
    }

    /** Replay pending offline track edits onto the current server head; clears the outbox on success. */
    override suspend fun replayPending(): Boolean = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext false
        val vk = vaultKeyHolder.get() ?: return@withContext false
        val delta = syncOutbox.pending(KEY, vk) ?: return@withContext true
        if (delta.isEmpty) { syncOutbox.clear(KEY); return@withContext true }
        if (!connectivity.isOnline()) return@withContext false
        val out = pushOptimistic(session, vk, cached = null) { m -> applyDelta(m, delta) }
        if (out is Outcome.Ok) { syncOutbox.clear(KEY); true } else false
    }

    /** The wrapped key + blob id of an uploaded raw track file (rawBlobKey = encFileKey `{c,n}`). */
    data class RawBlob(val id: String, val encKey: String)

    /**
     * Encrypt + upload the ORIGINAL imported file bytes (GPX/KML) to `/explore/upload` so the track
     * can later be re-exported byte-identically. Content is secretstream-encrypted + Padmé-padded
     * client-side (VK-wrapped per-file key). Null on failure — the import still succeeds without it.
     */
    suspend fun uploadRaw(bytes: ByteArray): RawBlob? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        val vk = vaultKeyHolder.get() ?: return@withContext null
        val enc = crypto.newContentEncryptor(vk)
        val body = EncryptedUpload.body(enc, crypto.contentChunkSize, bytes.size.toLong()) { java.io.ByteArrayInputStream(bytes) }
        runCatching {
            val part = okhttp3.MultipartBody.Part.createFormData("file", "track.enc", body)
            val r = apiProvider(session).exploreUpload(part)
            if (r.isSuccessful) RawBlob(r.body()!!.id, enc.sealKey()) else null
        }.getOrNull()
    }

    /** Fetch + decrypt an uploaded raw track file for exact re-export. Null on failure. */
    suspend fun downloadRaw(blobId: String, encKey: String): ByteArray? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        val vk = vaultKeyHolder.get() ?: return@withContext null
        runCatching {
            val r = apiProvider(session).exploreRaw(blobId)
            if (!r.isSuccessful) return@runCatching null
            val cipher = r.body()?.bytes() ?: return@runCatching null
            BlobDownloader.decrypt(cipher, encKey, vk, crypto)
        }.getOrNull()
    }

    /** Explore track-blob storage usage (used, quota) bytes, or null on failure (v1.536). */
    suspend fun usage(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        runCatching {
            apiProvider(session).exploreUsage().takeIf { it.isSuccessful }?.body()?.let { it.used to it.quota }
        }.getOrNull()
    }

    /** Reverse-geocode a coordinate to a place name via the server (coarse grid, never cached). */
    suspend fun reverse(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        runCatching { apiProvider(session).galleryReverse(lat, lng).body()?.place }.getOrNull()
    }

    /** Reverse-geocode to the full response (place + structured address parts). */
    suspend fun reverseAddress(lat: Double, lng: Double): de.ledgerline.app.data.remote.dto.ReverseResponse? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        runCatching { apiProvider(session).galleryReverse(lat, lng).body() }.getOrNull()
    }

    /** Snap waypoints to a routed path. Returns `[(lat,lng)…]` (null on failure / no route). */
    suspend fun route(waypoints: List<Pair<Double, Double>>): List<Pair<Double, Double>>? = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) return@withContext null
        val session = sessionHolder.get() ?: return@withContext null
        val points = waypoints.joinToString(";") { (la, ln) -> "${"%.6f".format(java.util.Locale.US, la)},${"%.6f".format(java.util.Locale.US, ln)}" }
        runCatching {
            apiProvider(session).mapsRoute(points).body()?.geometry
                ?.mapNotNull { if (it.size >= 2) it[0] to it[1] else null }
                ?.takeIf { it.size >= 2 }
        }.getOrNull()
    }

    private fun cachedOr(err: Outcome<ExploreStore>, vk: ByteArray): Outcome<ExploreStore> =
        cachedOrStore(
            cachingEnabled = offlineFlags.enabled(),
            envelope = storeCache.get(KEY),
            err = err,
            open = { ct -> crypto.openManifest(ct, vk) },
            decode = { plain -> decodeManifest(plain) },
            empty = { ExploreManifest() },
            wrap = { m, v -> ExploreStore(m, v) },
        )
}
