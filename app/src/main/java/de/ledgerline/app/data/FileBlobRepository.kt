package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.crypto.padByteCount
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.usecase.FileBlobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.security.SecureRandom
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
    private val apiProvider: (Session) -> LedgerlineApi,
) : FileBlobs {
    /** Production constructor used by Hilt (Hilt can't inject the default lambda). */
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
    ) : this(
        sessionHolder,
        vaultKeyHolder,
        crypto,
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
        // Framed ciphertext size = header + total + chunks*(ABYTES+4); pad to the Padmé bucket.
        val chunks = if (size == 0L) 1L else (size + crypto.contentChunkSize - 1) / crypto.contentChunkSize
        val framed = 24L + size + chunks * (17L + 4L)
        val pad = padByteCount(framed)
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                sink.write(enc.header)
                openInput().use { ins ->
                    val buf = ByteArray(crypto.contentChunkSize)
                    if (size == 0L) {
                        sink.write(enc.encryptChunk(ByteArray(0), true))
                    } else {
                        var remaining = size
                        while (remaining > 0) {
                            val want = minOf(buf.size.toLong(), remaining).toInt()
                            var read = 0
                            while (read < want) {
                                val r = ins.read(buf, read, want - read)
                                if (r < 0) break
                                read += r
                            }
                            // If the source delivered fewer bytes than declared (truncation /
                            // shrank between query and read), treat EOF as the final chunk so the
                            // last frame is TAG_FINAL and the loop can't spin forever.
                            val eof = read < want
                            val last = eof || remaining - read <= 0
                            sink.write(enc.encryptChunk(buf.copyOf(read), last))
                            remaining -= read
                            if (eof) break
                        }
                    }
                }
                if (pad > 0) {
                    val rnd = SecureRandom()
                    val block = ByteArray(64 * 1024)
                    var left = pad
                    while (left > 0) {
                        val n = minOf(block.size.toLong(), left).toInt()
                        rnd.nextBytes(block)
                        sink.write(block, 0, n)
                        left -= n
                    }
                }
            }
        }
        try {
            val part = MultipartBody.Part.createFormData("file", name, body)
            val res = apiProvider(session).uploadFile(part)
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            Outcome.Ok(UploadedBlob(res.body()!!.id, enc.sealKey(), size))
        } catch (e: Exception) {
            Outcome.Err(ErrorKind.NETWORK, e)
        }
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
     * frame-decrypted. Typical viewed/exported files fit comfortably; a fully
     * streamed okio `Source` refinement is a later optimization.
     */
    private suspend fun streamDecrypted(
        blob: String,
        encFileKey: String,
        consume: (ByteArray) -> Unit,
    ): Outcome<Unit> {
        val session = sessionHolder.get() ?: return Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return Outcome.Err(ErrorKind.DECRYPT)
        return try {
            val res = apiProvider(session).rawFile(blob)
            if (!res.isSuccessful) return Outcome.Err(ErrorKind.NETWORK)
            val bytes = res.body()!!.bytes()
            val dec = crypto.contentDecryptor(encFileKey, vk)
            dec.start(bytes.copyOfRange(0, dec.headerBytes))
            var off = dec.headerBytes
            while (off < bytes.size) {
                if (off + 4 > bytes.size) break
                val len = crypto.readU32le(bytes, off); off += 4
                if (len <= 0 || off + len > bytes.size) break // reached the Padmé tail
                val (msg, final) = dec.decryptFrame(bytes.copyOfRange(off, off + len)); off += len
                consume(msg)
                if (final) break
            }
            Outcome.Ok(Unit)
        } catch (e: Exception) {
            Outcome.Err(ErrorKind.DECRYPT, e)
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
            apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = null, allowCleartext = true) },
        )

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
