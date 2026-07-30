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
    private val degradedState: de.ledgerline.app.core.offline.DegradedState,
    private val blobCache: de.ledgerline.app.core.offline.BlobDiskCache,
    private val syncOutbox: de.ledgerline.app.core.offline.SyncOutbox,
    private val connectivity: de.ledgerline.app.core.offline.Connectivity,
    private val apiProvider: (Session) -> LedgerlineApi,
) : de.ledgerline.app.core.offline.SyncableStore {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        cache: GalleryCache,
        storeCache: StoreDiskCache,
        offlineFlags: OfflineFlags,
        galleryUpload: de.ledgerline.app.domain.usecase.GalleryUploadApi,
        degradedState: de.ledgerline.app.core.offline.DegradedState,
        blobCache: de.ledgerline.app.core.offline.BlobDiskCache,
        syncOutbox: de.ledgerline.app.core.offline.SyncOutbox,
        connectivity: de.ledgerline.app.core.offline.Connectivity,
    ) : this(
        sessionHolder,
        vaultKeyHolder,
        crypto,
        cache,
        storeCache,
        offlineFlags,
        galleryUpload,
        degradedState,
        blobCache,
        syncOutbox,
        connectivity,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    private companion object {
        const val KEY = "gallery"
        const val OUTBOX = "gallery"
    }

    override val syncLabel: String = "gallery"

    // ---- Offline write outbox (record-level delta; metadata edits only — new-photo imports
    // create blobs and still require a connection until the blob-upload queue lands) ----
    private fun collectionsOf(m: GalleryManifest): Map<String, Map<String, kotlinx.serialization.json.JsonObject>> = mapOf(
        "photos" to m.photos.associate { it.id to GalleryRecordCodec.encodePhoto(it, photoRawById[it.id]) },
        "albums" to m.albums.associate { it.id to GalleryRecordCodec.encodeAlbum(it, albumRawById[it.id]) },
        "people" to m.people.associate { it.id to GalleryRecordCodec.encodePerson(it, personRawById[it.id]) },
    )

    private fun applyDelta(m: GalleryManifest, delta: de.ledgerline.app.core.offline.StoreDelta): GalleryManifest {
        fun <T> merge(list: List<T>, key: String, id: (T) -> String, decode: (kotlinx.serialization.json.JsonObject) -> T): List<T> {
            val cd = delta.collections[key] ?: return list
            if (cd.isEmpty) return list
            val byId = list.associateByTo(LinkedHashMap()) { id(it) }
            cd.deletes.forEach { byId.remove(it) }
            cd.upserts.forEach { (rid, obj) -> byId[rid] = decode(obj) }
            return byId.values.toList()
        }
        return GalleryManifest(
            photos = merge(m.photos, "photos", { it.id }) { obj -> GalleryRecordCodec.decodePhoto(obj).also { photoRawById[it.id] = obj } },
            albums = merge(m.albums, "albums", { it.id }) { obj -> GalleryRecordCodec.decodeAlbum(obj).also { albumRawById[it.id] = obj } },
            people = merge(m.people, "people", { it.id }) { obj -> GalleryRecordCodec.decodePerson(obj).also { personRawById[it.id] = obj } },
        )
    }

    private fun withPending(m: GalleryManifest, vk: ByteArray): GalleryManifest =
        syncOutbox.pending(OUTBOX, vk)?.let { applyDelta(m, it) } ?: m

    private fun enqueueOffline(vk: ByteArray, base: GalleryManifest, next: GalleryManifest): Outcome<Gallery> {
        val delta = de.ledgerline.app.core.offline.StoreDelta.diff(collectionsOf(base), collectionsOf(next))
        if (!delta.isEmpty) syncOutbox.append(OUTBOX, delta, vk)
        val gallery = Gallery(next, cache.value.value?.version ?: 0)
        cache.set(gallery)
        return Outcome.Ok(gallery)
    }

    override suspend fun replayPending(): Boolean = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext false
        val delta = syncOutbox.pending(OUTBOX, vk) ?: return@withContext true
        if (delta.isEmpty) { syncOutbox.clear(OUTBOX); return@withContext true }
        if (!connectivity.isOnline()) return@withContext false
        if (degradedState.gallery.value) return@withContext false
        val session = sessionHolder.get() ?: return@withContext false
        // Diff onto the CLEAN server head (not the delta-layered cache) so the write is non-empty.
        val clean = try {
            val res = apiProvider(session).galleryStore()
            if (!res.isSuccessful) return@withContext false
            val body = res.body()!!
            val m = body.ciphertext?.let { ct ->
                val plain = crypto.openManifest(ct, vk) ?: return@withContext false
                assembleManifest(json.decodeFromString<GalleryRoot>(plain), session, vk, allowNetwork = true)
            } ?: GalleryManifest()
            Gallery(m, body.version)
        } catch (_: Exception) {
            return@withContext false
        }
        val out = saveOnline(baseOverride = clean) { m -> applyDelta(m, delta) }
        if (out is Outcome.Ok) { syncOutbox.clear(OUTBOX); true } else false
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
        // Cache-first: paint the last-cached gallery immediately (offline-assembled from the disk
        // cache) so the UI shows content before the network round-trip; the fetch below then
        // refreshes it. Best-effort — a cold cache just falls through to the network load.
        if (cache.value.value == null) {
            (cachedOr(Outcome.Err(ErrorKind.NETWORK), session, vk) as? Outcome.Ok)?.let { cache.set(Gallery(withPending(it.value.manifest, vk), it.value.version)) }
        }
        try {
            val res = apiProvider(session).galleryStore()
            when {
                // 401 stays an auth failure → forced-logout path; never fall back to cache.
                res.code() == HttpURLConnection.HTTP_UNAUTHORIZED -> Outcome.Err(ErrorKind.HTTP)
                !res.isSuccessful -> cachedOr(Outcome.Err(ErrorKind.NETWORK), session, vk)
                else -> {
                    val body = res.body()!!
                    val manifest = body.ciphertext?.let { ct ->
                        val plain = crypto.openManifest(ct, vk) ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
                        assembleManifest(json.decodeFromString<GalleryRoot>(plain), session, vk, allowNetwork = true)
                    } ?: GalleryManifest()
                    if (offlineFlags.enabled()) {
                        storeCache.put(KEY, StoreEnvelope(body.ciphertext, body.version))
                    }
                    // Layer any un-synced offline edits on top of the fresh server manifest.
                    var gallery = Gallery(withPending(manifest, vk), body.version)
                    // Self-heal a degraded index: a record shard was permanently missing (404), so its
                    // records are gone for good — re-seal the root from only the loaded shards so it no
                    // longer points at the dead blob (mirrors the web self-heal). Then reclaim orphaned
                    // blobs (the gallery never ran a reconcile pass before). Both best-effort; a full
                    // ONLINE load only reaches here after every recoverable record decrypted.
                    if (degradedState.gallery.value) {
                        healDegraded(session, vk, manifest, body.version)?.let { gallery = Gallery(withPending(manifest, vk), it) }
                    }
                    reconcileLiveSet(session, manifest)
                    Outcome.Ok(gallery)
                }
            }
        } catch (e: Exception) { cachedOr(Outcome.Err(ErrorKind.NETWORK, e), session, vk) }
    }

    /**
     * Build the in-memory [GalleryManifest] from the raw root. v2 fetches + decrypts each
     * photo shard blob and concatenates the records; v1 uses the inline photos. A failing
     * shard fetch THROWS (fails the whole load, mirroring the web client) rather than
     * silently dropping photos — losing photos here would let a reconcile free their blobs.
     * The assembled manifest is v1-shaped, so a later save writes inline photos (web reads
     * that as legacy and re-shards on its next save).
     */
    private suspend fun assembleManifest(root: GalleryRoot, session: Session, vk: ByteArray, allowNetwork: Boolean): GalleryManifest {
        val api = apiProvider(session)
        // Remember the sealed-root state + the raw records so the next save reuses unchanged
        // blobs and re-emits every web field byte-exact (no data loss).
        priorRoot = rootStateFrom(root)
        photoRawById.clear(); albumRawById.clear(); personRawById.clear()

        degradedState.setGallery(false)
        val photos = if (root.v >= 2 && root.shards.isNotEmpty()) {
            // Fetch + decrypt + decode all shards concurrently. A durable 404 marks the store
            // degraded + skips that shard (descriptor kept, writes frozen); other errors throw.
            coroutineScope {
                root.shards.map { s -> async(Dispatchers.IO) { fetchGalleryShard(api, s, vk, allowNetwork) } }.awaitAll()
            }.flatMap { it ?: emptyList() }.map { obj ->
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
            fetchCollectionRaw(api, root.albumsRef!!, root.albumsKey ?: "", vk, allowNetwork).map { obj ->
                GalleryRecordCodec.decodeAlbum(obj).also { albumRawById[it.id] = obj }
            }
        } else {
            root.albums
        }
        val people = if (root.peopleRef != null) {
            fetchCollectionRaw(api, root.peopleRef!!, root.peopleKey ?: "", vk, allowNetwork).map { obj ->
                GalleryRecordCodec.decodePerson(obj).also { personRawById[it.id] = obj }
            }
        } else {
            root.people
        }
        return GalleryManifest(photos = photos, albums = albums, people = people)
    }

    /**
     * Raw ciphertext for a content-addressed gallery blob (shard/collection). **Cache-first:**
     * refs are content-addressed (a ref only ever names one immutable payload), so a cached hit
     * is always current and skips the network entirely — this is what makes offline assembly
     * work and speeds up warm online loads. On a miss with [allowNetwork], fetch (404-retried),
     * persist the ciphertext when offline caching is on, and mark degraded on a persistent 404
     * (only when [markDegraded]). Offline (`allowNetwork=false`) a miss returns null — that slice
     * is simply unavailable until the next online load caches it.
     */
    private suspend fun blobCipher(
        api: LedgerlineApi,
        ref: String,
        allowNetwork: Boolean,
        markDegraded: Boolean,
    ): ByteArray? {
        blobCache.get(ref)?.let { return it }
        if (!allowNetwork) return null
        var attempt = 0
        while (true) {
            val r = api.galleryRaw(ref)
            if (r.isSuccessful) {
                val bytes = r.body()!!.bytes()
                if (offlineFlags.enabled()) blobCache.put(ref, bytes)
                return bytes
            }
            if (r.code() == HttpURLConnection.HTTP_NOT_FOUND) {
                if (attempt < 3) { kotlinx.coroutines.delay(500L * (1 shl attempt)); attempt++; continue }
                if (markDegraded) degradedState.setGallery(true)
                return null
            }
            error("gallery blob $ref: http ${r.code()}")
        }
    }

    /**
     * Fetch + decrypt one photo shard's records. A durable 404 marks the store degraded and
     * returns null (records skipped, descriptor kept, writes frozen). Offline: a cache miss
     * returns null (those photos are unavailable until the next online load). Non-404 throws.
     */
    private suspend fun fetchGalleryShard(
        api: LedgerlineApi,
        s: de.ledgerline.app.domain.model.GalleryShard,
        vk: ByteArray,
        allowNetwork: Boolean,
    ): List<kotlinx.serialization.json.JsonObject>? {
        val cipher = blobCipher(api, s.ref, allowNetwork, markDegraded = true) ?: return null
        val bytes = BlobDownloader.decrypt(cipher, s.key, vk, crypto)
        return json.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject }
    }

    /**
     * Download + decrypt a collection blob (albums/people) into its raw record objects. Online
     * a fetch failure throws (never silently lose albums/people); offline a cache miss yields
     * an empty list (that collection is unavailable until the next online load).
     */
    private suspend fun fetchCollectionRaw(
        api: LedgerlineApi,
        ref: String,
        key: String,
        vk: ByteArray,
        allowNetwork: Boolean,
    ): List<kotlinx.serialization.json.JsonObject> {
        val cipher = blobCipher(api, ref, allowNetwork, markDegraded = false)
            ?: if (allowNetwork) error("gallery collection $ref: unavailable") else return emptyList()
        val bytes = BlobDownloader.decrypt(cipher, key, vk, crypto)
        return json.parseToJsonElement(bytes.decodeToString()).jsonArray.map { it.jsonObject }
    }

    /**
     * Network-first cache fallback: when a fetch fails with a non-auth error, decrypt the
     * on-disk sealed **root** and assemble the manifest from the locally cached shard/collection
     * blobs (no network). Photos/albums/people whose blobs were never prefetched are simply
     * absent offline. Returns [err] unchanged if offline caching is off, no entry exists, or
     * the root fails to decrypt/decode.
     */
    private suspend fun cachedOr(err: Outcome<Gallery>, session: Session, vk: ByteArray): Outcome<Gallery> {
        if (!offlineFlags.enabled()) return err
        val env = storeCache.get(KEY) ?: return err
        val ct = env.ciphertext ?: return Outcome.Ok(Gallery(GalleryManifest(), env.version))
        val plain = crypto.openManifest(ct, vk) ?: return err
        return try {
            val root = json.decodeFromString<GalleryRoot>(plain)
            val manifest = assembleManifest(root, session, vk, allowNetwork = false)
            Outcome.Ok(Gallery(manifest, env.version))
        } catch (_: Exception) {
            err
        }
    }

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
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        if (degradedState.gallery.value) return@withContext Outcome.Err(ErrorKind.HTTP)
        val base = cache.value.value?.manifest
            ?: (load() as? Outcome.Ok)?.value?.manifest
            ?: GalleryManifest()
        // Offline: queue the metadata edit + optimistic cache. (New-photo imports create blobs and
        // fail earlier at the uploader, so they never reach here offline.)
        if (!connectivity.isOnline()) return@withContext enqueueOffline(vk, base, mutate(base))
        val out = saveOnline(mutate = mutate)
        if (out is Outcome.Err && out.kind == ErrorKind.NETWORK) enqueueOffline(vk, base, mutate(base)) else out
    }

    private suspend fun saveOnline(
        baseOverride: Gallery? = null,
        mutate: (GalleryManifest) -> GalleryManifest,
    ): Outcome<Gallery> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        // Frozen while degraded: a shard blob is missing; rewriting the root would drop it for good.
        if (degradedState.gallery.value) return@withContext Outcome.Err(ErrorKind.HTTP)
        val api = apiProvider(session)

        var base = baseOverride ?: cache.value.value
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
                        assembleManifest(json.decodeFromString<GalleryRoot>(plain), session, vk, allowNetwork = true)
                    } ?: GalleryManifest().also { priorRoot = GalleryShardWriter.RootState() }
                    next = mutate(serverManifest)
                }
                else -> return@withContext Outcome.Err(ErrorKind.HTTP)
            }
        }
        Outcome.Err(ErrorKind.HTTP) // gave up after retries
    }

    /**
     * Every blob id the manifest still references — per-photo renditions + face crops, person face
     * crops, and the sealed root's shard + collection blobs. Sent to the server so it can free the
     * quota held by any blob NOT in this set (grace-gated, 24 h). Album `cover` is a photo id, not a
     * blob, so it is excluded. NEVER call with a partial/offline manifest — that would free live blobs.
     */
    private fun livingSet(m: GalleryManifest): List<String> {
        val out = ArrayList<String>()
        for (p in m.photos) {
            listOfNotNull(p.originalRef, p.thumbRef, p.mediumRef, p.motionRef, p.metaRef).forEach(out::add)
            out.addAll(p.faceCropRefs)
        }
        for (person in m.people) person.faces.forEach { f -> f.cropRef?.let(out::add) }
        priorRoot.shards.forEach { out.add(it.ref) }
        priorRoot.albums?.ref?.let(out::add)
        priorRoot.people?.ref?.let(out::add)
        return out.distinct()
    }

    /** Best-effort reconcile of the manifest's living blob set (reclaims orphaned gallery blobs). */
    private suspend fun reconcileLiveSet(session: Session, manifest: GalleryManifest) {
        val living = livingSet(manifest)
        if (living.isEmpty()) return
        try {
            apiProvider(session).galleryReconcile(de.ledgerline.app.data.remote.dto.ReconcileRequest(living))
        } catch (_: Exception) { /* best-effort — reclaimed on a later load */ }
    }

    /**
     * Self-heal a degraded index: re-seal the root from the loaded (partial) records so it no longer
     * references the permanently-missing shard, clearing the degraded freeze. Returns the new version
     * on success, or null (leaves it degraded to retry next load). Best-effort — never throws.
     */
    private suspend fun healDegraded(session: Session, vk: ByteArray, manifest: GalleryManifest, version: Int): Int? {
        return try {
            val result = shardWriter.build(manifest.photos, manifest.albums, manifest.people, priorRoot) ?: return null
            val rootCipher = crypto.sealManifest(CanonicalJson.encode(result.rootJson), vk)
            val put = apiProvider(session).galleryStorePut(StorePutRequest(rootCipher, version, result.shardRefs))
            if (!put.isSuccessful) return null
            val nv = put.body()?.version ?: (version + 1)
            priorRoot = result.state
            degradedState.setGallery(false)
            if (offlineFlags.enabled()) storeCache.put(KEY, StoreEnvelope(rootCipher, nv))
            cache.set(Gallery(manifest, nv))
            nv
        } catch (_: Exception) { null }
    }
}
