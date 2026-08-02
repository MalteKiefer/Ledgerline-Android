package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.OfflineFlags
import androidx.annotation.VisibleForTesting
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.offline.PhotoBlobPolicy
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.EmbedTextRequest
import de.ledgerline.app.data.remote.dto.ProcessResponse
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.usecase.EmbedText
import de.ledgerline.app.domain.usecase.GalleryBlobs
import de.ledgerline.app.domain.usecase.GalleryUploadApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryBlobRepository @VisibleForTesting internal constructor(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val blobCache: BlobDiskCache,
    private val offlineFlags: OfflineFlags,
    private val apiProvider: (Session) -> LedgerlineApi,
) : GalleryBlobs, GalleryUploadApi, EmbedText {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        blobCache: BlobDiskCache,
        offlineFlags: OfflineFlags,
    ) : this(sessionHolder, vaultKeyHolder, crypto, blobCache, offlineFlags, { s -> NetworkFactory.create(s.baseUrl, { s.token }, s.spkiPin) })

    /** Photo ciphertext is cached on access unless the master switch or the policy is off. */
    private fun cachingEnabled(): Boolean =
        offlineFlags.enabled() && offlineFlags.photosPolicy() != PhotoBlobPolicy.OFF

    override suspend fun download(ref: String, key: String): Outcome<ByteArray> = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        // Cache-first: gallery content blobs are content-addressed and immutable, so a cache
        // hit is always current — decrypt from disk and skip the network entirely. This makes
        // scroll-back and repeat opens instant instead of re-fetching every thumb (the per-blob
        // throttle otherwise serialises thousands of thumbs into minutes).
        if (cachingEnabled()) {
            blobCache.get(ref)?.let { cached ->
                runCatching { BlobDownloader.decrypt(cached, key, vk, crypto) }.getOrNull()
                    ?.let { return@withContext Outcome.Ok(it) }
            }
        }
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        try {
            val res = apiProvider(session).galleryRaw(ref)
            // Non-2xx (except the 401 auth path) may fall back to the ciphertext cache.
            if (!res.isSuccessful) {
                return@withContext if (res.code() == java.net.HttpURLConnection.HTTP_UNAUTHORIZED) {
                    Outcome.Err(ErrorKind.NETWORK)
                } else {
                    cachedOr(ref, key, vk, Outcome.Err(ErrorKind.NETWORK))
                }
            }
            val bytes = res.body()!!.bytes()
            if (cachingEnabled()) blobCache.put(ref, bytes)
            Outcome.Ok(BlobDownloader.decrypt(bytes, key, vk, crypto))
        } catch (e: Exception) { cachedOr(ref, key, vk, Outcome.Err(ErrorKind.DECRYPT, e)) }
    }

    /**
     * Network-first cache fallback for a gallery blob: when the fetch fails, try the
     * on-disk ciphertext cache (if photos caching is on) and decrypt it in-memory.
     * Returns [err] if disabled, absent, or decryption fails.
     */
    private fun cachedOr(ref: String, key: String, vk: ByteArray, err: Outcome<ByteArray>): Outcome<ByteArray> {
        if (!cachingEnabled()) return err
        val bytes = blobCache.get(ref) ?: return err
        return try {
            Outcome.Ok(BlobDownloader.decrypt(bytes, key, vk, crypto))
        } catch (_: Exception) {
            err
        }
    }

    /**
     * Fetch a blob's ciphertext and cache it. No decryption, no VK. Skips if already
     * cached. Returns true on cache-hit or stored, false on failure. Used by the
     * prefetch engine; the caller decides *what* to prefetch, so this always caches
     * when asked (the policy flag does not gate it).
     */
    suspend fun prefetch(ref: String): Boolean = withContext(Dispatchers.IO) {
        if (blobCache.has(ref)) return@withContext true
        val session = sessionHolder.get() ?: return@withContext false
        try {
            val res = apiProvider(session).galleryRaw(ref)
            if (!res.isSuccessful) return@withContext false
            blobCache.put(ref, res.body()!!.bytes())
            true
        } catch (_: Exception) { false }
    }

    /**
     * Batch-prefetch many blobs' ciphertext via `POST /gallery/raw-batch` (one round-trip per
     * ≤512-blob chunk instead of one per blob) and write each through to the disk cache. Already-
     * cached ids are skipped. Returns the number newly cached; a failed chunk is a no-op (the
     * per-blob [prefetch] path still covers those ids later).
     */
    suspend fun prefetchBatch(refs: List<String>): Int = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext 0
        val pending = refs.distinct().filterNot { blobCache.has(it) }
        if (pending.isEmpty()) return@withContext 0
        var cached = 0
        for (chunk in pending.chunked(512)) {
            try {
                val res = apiProvider(session).galleryRawBatch(
                    de.ledgerline.app.data.remote.dto.ReconcileRequest(chunk),
                )
                if (!res.isSuccessful) continue
                RawBatchFraming.parse(res.body()!!.bytes()).forEach { (id, cipher) ->
                    blobCache.put(id, cipher); cached++
                }
            } catch (_: Exception) { /* fall back to per-blob prefetch */ }
        }
        cached
    }

    override suspend fun uploadBytes(bytes: ByteArray, name: String): Outcome<UploadedBlob> =
        uploadStream(name, bytes.size.toLong()) { java.io.ByteArrayInputStream(bytes) }

    override suspend fun uploadStream(name: String, size: Long, openInput: () -> java.io.InputStream): Outcome<UploadedBlob> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val enc = crypto.newContentEncryptor(vk)
        val api = apiProvider(session)
        // Large originals (videos) go via the S3-multipart chunked path (constant memory); smaller
        // blobs stream single-shot. Either way the plaintext is never fully held in RAM.
        if (size >= ChunkedUpload.THRESHOLD) {
            return@withContext ChunkedUpload.upload(
                encryptor = enc, chunkSize = crypto.contentChunkSize, size = size, openInput = openInput,
                tempDir = blobCache.tempDir(),
                init = { encSize -> api.galleryUploadInit(de.ledgerline.app.data.remote.dto.UploadInitRequest(encSize)).bodyOrNull() },
                part = { token, num, chunk -> api.galleryUploadPart(token.textPart(), num.toString().textPart(), chunk.chunkPart(num)).bodyOrNull() },
                complete = { token, parts -> api.galleryUploadComplete(de.ledgerline.app.data.remote.dto.UploadCompleteRequest(token, parts)).bodyOrNull()?.id },
                abort = { token -> runCatching { api.galleryUploadAbort(de.ledgerline.app.data.remote.dto.UploadAbortRequest(token)) } },
            )
        }
        val body = EncryptedUpload.body(enc, crypto.contentChunkSize, size, openInput)
        try {
            val part = MultipartBody.Part.createFormData("file", name, body)
            val res = api.galleryUpload(part)
            if (!res.isSuccessful) {
                // 413 = storage quota exceeded (server-defined) — a distinct, non-retryable failure.
                return@withContext Outcome.Err(if (res.code() == 413) ErrorKind.QUOTA else ErrorKind.NETWORK)
            }
            Outcome.Ok(UploadedBlob(res.body()!!.id, enc.sealKey(), size))
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    /**
     * Delete freed gallery blobs, honoring `Retry-After` on 429 (backoff capped at
     * 30 s, max 3 attempts per blob). Sequential is fine — the bulk sizes here are small.
     */
    override suspend fun deleteBlobs(refs: List<String>) = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext
        val api = apiProvider(session)
        deleteBlobsWithBackoff(refs) { api.deleteGalleryBlob(it) }
    }

    override suspend fun process(name: String, mime: String, size: Long, openInput: () -> java.io.InputStream): Outcome<ProcessResponse> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        try {
            // Stream the plaintext to the server (never held fully in RAM — large videos would OOM).
            val part = MultipartBody.Part.createFormData("file", name, plaintextStreamBody(mime, size, openInput))
            val res = apiProvider(session).galleryProcess(part)
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            Outcome.Ok(res.body()!!)
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    /**
     * Web `_reembedOne`: re-embed a photo's CLIP search vector via `POST /gallery/analyze` and
     * reseal its meta blob with the fresh embedding + `embModel` — **faces untouched** (a CLIP
     * model swap only affects search; the face model is unchanged). Decrypts the medium (or
     * original) rendition transiently, POSTs the plaintext, merges into the existing meta JSON
     * (all other fields preserved verbatim), and uploads a NEW meta blob. Returns the new
     * ref/key, or null on any failure (the caller keeps the old meta). The superseded meta blob
     * becomes orphaned and is reclaimed by reconcile-on-load.
     */
    override suspend fun reembed(mediumRef: String, mediumKey: String, metaRef: String, metaKey: String): de.ledgerline.app.domain.usecase.Reembed? =
        withContext(Dispatchers.IO) {
            val session = sessionHolder.get() ?: return@withContext null
            val plain = when (val r = download(mediumRef, mediumKey)) {
                is Outcome.Ok -> r.value; is Outcome.Err -> return@withContext null
            }
            val resp = try {
                val body = plain.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", "medium.jpg", body)
                apiProvider(session).galleryAnalyze(part)
            } catch (_: Exception) { return@withContext null }
            if (!resp.isSuccessful) return@withContext null
            val d = resp.body() ?: return@withContext null
            val emb = d.embedding as? kotlinx.serialization.json.JsonArray ?: return@withContext null
            if (emb.isEmpty()) return@withContext null

            val metaBytes = when (val r = download(metaRef, metaKey)) {
                is Outcome.Ok -> r.value; is Outcome.Err -> return@withContext null
            }
            val meta = try {
                kotlinx.serialization.json.Json.parseToJsonElement(String(metaBytes))
                    as? kotlinx.serialization.json.JsonObject ?: return@withContext null
            } catch (_: Exception) { return@withContext null }
            val overlay = buildMap<String, kotlinx.serialization.json.JsonElement> {
                put("embedding", emb)
                d.model?.let { put("embModel", kotlinx.serialization.json.JsonPrimitive(it)) }
            }
            val merged = kotlinx.serialization.json.JsonObject(meta + overlay)
            val out = when (val r = uploadBytes(
                kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), merged).toByteArray(),
                "meta.enc",
            )) { is Outcome.Ok -> r.value; is Outcome.Err -> return@withContext null }
            de.ledgerline.app.domain.usecase.Reembed(out.id, out.encFileKey, d.model)
        }

    override suspend fun invoke(query: String): List<Double>? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        try {
            val res = apiProvider(session).embedText(EmbedTextRequest(query))
            if (!res.isSuccessful) return@withContext null
            res.body()?.embedding?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

}
