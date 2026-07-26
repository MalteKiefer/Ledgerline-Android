package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.data.remote.dto.PartRef
import de.ledgerline.app.data.remote.dto.UploadInitResponse
import de.ledgerline.app.data.remote.dto.UploadPartResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
import retrofit2.Response
import java.io.File
import java.io.InputStream

/** Retrofit/multipart helpers shared by the files + gallery chunked-upload wiring. */
fun <T> Response<T>.bodyOrNull(): T? = if (isSuccessful) body() else null
fun String.textPart(): RequestBody = toRequestBody("text/plain".toMediaType())
fun ByteArray.chunkPart(num: Int): MultipartBody.Part =
    MultipartBody.Part.createFormData("chunk", "part$num", toRequestBody("application/octet-stream".toMediaType()))

/**
 * S3-multipart chunked upload for large blobs (files/videos ≥ [THRESHOLD]). The blob is
 * stream-encrypted (secretstream + Padmé, constant memory) to a temp file, then uploaded
 * in server-sized parts: `init → part…part → complete` (or `abort` on failure). Keeps at
 * most one part in memory, so a multi-GB video never OOMs (the single-shot streaming POST
 * is used below the threshold).
 *
 * Prefix-agnostic: the init/part/complete/abort calls are injected so the same engine
 * serves both the files and the gallery upload routes and is unit-testable.
 */
object ChunkedUpload {
    const val THRESHOLD = 64L * 1024 * 1024 // plaintext size at/above which we go multipart

    suspend fun upload(
        encryptor: Crypto.ContentEncryptor,
        chunkSize: Int,
        size: Long,
        openInput: () -> InputStream,
        tempDir: File,
        init: suspend (encryptedSize: Long) -> UploadInitResponse?,
        part: suspend (token: String, partNum: Int, chunk: ByteArray) -> UploadPartResponse?,
        complete: suspend (token: String, parts: List<PartRef>) -> String?,
        abort: suspend (token: String) -> Unit,
    ): Outcome<UploadedBlob> {
        val temp = File.createTempFile("ll-upl", ".enc", tempDir)
        var token: String? = null
        try {
            // 1. Stream-encrypt the plaintext to the temp file (constant memory).
            val body = EncryptedUpload.body(encryptor, chunkSize, size, openInput)
            temp.sink().buffer().use { body.writeTo(it) }
            val encryptedSize = temp.length()
            val encFileKey = encryptor.sealKey()

            // 2. Start the multipart upload.
            val start = init(encryptedSize) ?: return Outcome.Err(ErrorKind.NETWORK)
            token = start.token
            val partSize = start.partSize.coerceAtLeast(1).toInt()

            // 3. Upload each part.
            val parts = mutableListOf<PartRef>()
            temp.inputStream().use { ins ->
                val buf = ByteArray(partSize)
                var partNum = 1
                while (true) {
                    var read = 0
                    while (read < buf.size) {
                        val r = ins.read(buf, read, buf.size - read)
                        if (r < 0) break
                        read += r
                    }
                    if (read == 0) break
                    val chunk = if (read == buf.size) buf else buf.copyOf(read)
                    val pr = part(start.token, partNum, chunk) ?: run { abort(start.token); return Outcome.Err(ErrorKind.NETWORK) }
                    parts.add(PartRef(pr.part, pr.etag))
                    partNum++
                    if (read < buf.size) break // last (short) part
                }
            }

            // 4. Finalize.
            val id = complete(start.token, parts) ?: run { abort(start.token); return Outcome.Err(ErrorKind.NETWORK) }
            token = null
            return Outcome.Ok(UploadedBlob(id, encFileKey, size))
        } catch (e: Exception) {
            token?.let { runCatching { abort(it) } }
            return Outcome.Err(ErrorKind.NETWORK, e)
        } finally {
            temp.delete()
        }
    }
}
