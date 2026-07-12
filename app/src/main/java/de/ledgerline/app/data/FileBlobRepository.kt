package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.offline.FileBlobPolicy
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.usecase.FileBlobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A freshly uploaded content blob: its server ref, wrapped per-file key, and plaintext size. */
data class UploadedBlob(val id: String, val encFileKey: String, val size: Long)

/**
 * Streams content blobs to/from the pinned, authenticated session:
 *  - [upload] stream-encrypts (secretstream) + Padmé-pads with constant memory,
 *  - [downloadToBytes] / [downloadTo] fetch + frame-decrypt,
 *  - [deleteBlobs] releases freed blobs, honoring 429 Retry-After backoff.
 *
 * The manifest write lives in [WorkspaceRepository.save]; this repo only moves blobs.
 */
@Singleton
class FileBlobRepository(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val blobCache: BlobDiskCache,
    private val offlineFlags: OfflineFlags,
    private val apiProvider: (Session) -> LedgerlineApi,
) : FileBlobs {
    /** File-content ciphertext is cached on access unless the master switch or the policy is off. */
    private fun cachingEnabled(): Boolean =
        offlineFlags.enabled() && offlineFlags.filesPolicy() != FileBlobPolicy.OFF

    /** Production constructor used by Hilt (Hilt can't inject the default lambda). */
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        blobCache: BlobDiskCache,
        offlineFlags: OfflineFlags,
    ) : this(
        sessionHolder,
        vaultKeyHolder,
        crypto,
        blobCache,
        offlineFlags,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    /**
     * Stream-encrypt [openInput] (plaintext length [size]) with a fresh per-file key,
     * append Padmé random padding, and upload. Returns the blob ref + wrapped key.
     * Runs with constant memory: chunks are framed and written straight to the sink.
     */
    override suspend fun upload(
        name: String,
        mime: String,
        size: Long,
        openInput: () -> InputStream,
    ): Outcome<UploadedBlob> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val enc = crypto.newContentEncryptor(vk)
        val body = EncryptedUpload.body(enc, crypto.contentChunkSize, size, openInput)
        try {
            val part = MultipartBody.Part.createFormData("file", name, body)
            val res = apiProvider(session).uploadFile(part)
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            Outcome.Ok(UploadedBlob(res.body()!!.id, enc.sealKey(), size))
        } catch (e: Exception) {
            Outcome.Err(ErrorKind.NETWORK, e)
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
            val res = apiProvider(session).rawFile(ref)
            if (!res.isSuccessful) return@withContext false
            blobCache.put(ref, res.body()!!.bytes())
            true
        } catch (_: Exception) { false }
    }

    /** Download + decrypt a blob fully into memory (for in-app viewing / small files). */
    override suspend fun downloadToBytes(blob: String, encFileKey: String): Outcome<ByteArray> =
        withContext(Dispatchers.IO) {
            val out = java.io.ByteArrayOutputStream()
            when (val r = streamDecrypted(blob, encFileKey) { chunk -> out.write(chunk) }) {
                is Outcome.Ok -> Outcome.Ok(out.toByteArray())
                is Outcome.Err -> r
            }
        }

    /** Stream-decrypt a blob, invoking [write] per plaintext chunk (for SAF export). */
    override suspend fun downloadTo(blob: String, encFileKey: String, write: (ByteArray) -> Unit): Outcome<Unit> =
        withContext(Dispatchers.IO) { streamDecrypted(blob, encFileKey, write) }

    /**
     * Fetch a blob and frame-decrypt it, feeding each plaintext chunk to [consume].
     *
     * NOTE: kept simple for Phase 3 — the full ciphertext is buffered, then
     * frame-decrypted via [BlobDownloader.decrypt]. Typical viewed/exported files
     * fit comfortably; a fully streamed okio `Source` refinement is a later optimization.
     */
    private suspend fun streamDecrypted(
        blob: String,
        encFileKey: String,
        consume: (ByteArray) -> Unit,
    ): Outcome<Unit> {
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        // Cache-first: file content blobs are content-addressed and immutable, so a cache
        // hit is always current — decrypt from disk and skip the network entirely.
        if (cachingEnabled()) {
            blobCache.get(blob)?.let { cached ->
                val plain = runCatching { BlobDownloader.decrypt(cached, encFileKey, vk, crypto) }.getOrNull()
                if (plain != null) {
                    consume(plain)
                    return Outcome.Ok(Unit)
                }
            }
        }
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        return try {
            val res = apiProvider(session).rawFile(blob)
            // Non-2xx (except the 401 auth path) may fall back to the ciphertext cache.
            if (!res.isSuccessful) {
                return if (res.code() == java.net.HttpURLConnection.HTTP_UNAUTHORIZED) {
                    Outcome.Err(ErrorKind.NETWORK)
                } else {
                    cachedOr(blob, encFileKey, vk, Outcome.Err(ErrorKind.NETWORK), consume)
                }
            }
            val bytes = res.body()!!.bytes()
            if (cachingEnabled()) blobCache.put(blob, bytes)
            val plain = BlobDownloader.decrypt(bytes, encFileKey, vk, crypto)
            consume(plain)
            Outcome.Ok(Unit)
        } catch (e: Exception) {
            cachedOr(blob, encFileKey, vk, Outcome.Err(ErrorKind.DECRYPT, e), consume)
        }
    }

    /**
     * Network-first cache fallback for a blob: when the fetch fails, try the on-disk
     * ciphertext cache (if file-contents caching is on), decrypt it in-memory, and
     * feed it to [consume]. Returns [err] if disabled, absent, or decryption fails.
     */
    private fun cachedOr(
        blob: String,
        encFileKey: String,
        vk: ByteArray,
        err: Outcome<Unit>,
        consume: (ByteArray) -> Unit,
    ): Outcome<Unit> {
        if (!cachingEnabled()) return err
        val bytes = blobCache.get(blob) ?: return err
        return try {
            consume(BlobDownloader.decrypt(bytes, encFileKey, vk, crypto))
            Outcome.Ok(Unit)
        } catch (_: Exception) {
            err
        }
    }

    /**
     * Delete freed blobs, honoring `Retry-After` on 429 (backoff capped at 30 s,
     * max 3 attempts per blob). Sequential is fine for Phase 3.
     */
    override suspend fun deleteBlobs(blobs: List<String>) = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext
        val api = apiProvider(session)
        for (id in blobs) {
            var attempt = 0
            while (attempt < 3) {
                val res = try { api.deleteBlob(id) } catch (_: Exception) { break }
                if (res.code() == 429) {
                    val retryAfterMs = res.headers()["Retry-After"]?.toLongOrNull()?.times(1000)
                        ?: (1000L shl attempt)
                    delay(minOf(retryAfterMs, 30_000L))
                    attempt++
                } else {
                    break
                }
            }
        }
    }

    companion object {
        /**
         * Test factory: wires a cleartext api provider so a plain-HTTP MockWebServer
         * can be driven from JVM unit tests. [deleteBlobs] never touches crypto, so a
         * throwing [Crypto] stub is used (avoids loading the native libsodium library,
         * which isn't available off-device).
         */
        internal fun forTest(baseUrl: String): FileBlobRepository = FileBlobRepository(
            sessionHolder = SessionHolder().apply { set(Session(baseUrl, "tok", "", null)) },
            vaultKeyHolder = VaultKeyHolder().apply { set(ByteArray(32)) },
            crypto = UnusedCrypto,
            blobCache = BlobDiskCache(File(System.getProperty("java.io.tmpdir"), "t-blob-" + System.nanoTime())),
            offlineFlags = NoOfflineFlags,
            apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = null, allowCleartext = true) },
        )

        /** Offline flags stub used where caching must stay inert (all toggles off). */
        private val NoOfflineFlags = object : OfflineFlags {
            override fun enabled() = false
            override fun filesPolicy() = FileBlobPolicy.OFF
            override fun photosPolicy() = de.ledgerline.app.data.offline.PhotoBlobPolicy.OFF
            override fun contactsPolicy() = de.ledgerline.app.data.offline.ContactBlobPolicy.OFF
            override fun maxBytes() = 0L
            override fun wifiOnly() = false
            override fun chargingOnly() = false
        }

        /** A [Crypto] that throws on every call; only used where crypto is never invoked. */
        private val UnusedCrypto = object : Crypto {
            override fun deriveKek(passphrase: ByteArray, salt: ByteArray, opsLimit: Long, memLimit: Long) =
                throw NotImplementedError()
            override fun secretBoxOpen(cipher: ByteArray, nonce: ByteArray, key: ByteArray) =
                throw NotImplementedError()
            override fun genericHash32(input: ByteArray) = throw NotImplementedError()
            override fun b64decode(s: String) = throw NotImplementedError()
            override fun b64encode(b: ByteArray) = throw NotImplementedError()
            override fun fromHex(s: String) = throw NotImplementedError()
            override fun openManifest(ciphertext: String, vk: ByteArray) = throw NotImplementedError()
            override fun sealManifest(json: String, vk: ByteArray) = throw NotImplementedError()
            override val contentChunkSize get() = throw NotImplementedError()
            override fun newContentEncryptor(vk: ByteArray) = throw NotImplementedError()
            override fun contentDecryptor(encFileKey: String, vk: ByteArray) = throw NotImplementedError()
            override fun u32le(n: Int) = throw NotImplementedError()
            override fun readU32le(bytes: ByteArray, off: Int) = throw NotImplementedError()
        }
    }
}
