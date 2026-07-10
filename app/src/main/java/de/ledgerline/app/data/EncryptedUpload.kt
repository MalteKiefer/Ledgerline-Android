package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.crypto.padByteCount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.InputStream
import java.security.SecureRandom

/** Builds a streaming RequestBody that writes header ++ framed secretstream chunks
 *  ++ Padmé random tail, encrypting on the fly (constant memory). The caller reads
 *  the wrapped key from [encryptor].sealKey() AFTER the request is sent. */
object EncryptedUpload {
    fun body(encryptor: Crypto.ContentEncryptor, chunkSize: Int, size: Long, openInput: () -> InputStream): RequestBody {
        val chunks = if (size == 0L) 1L else (size + chunkSize - 1) / chunkSize
        val framed = 24L + size + chunks * (17L + 4L)
        val pad = padByteCount(framed)
        return object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                sink.write(encryptor.header)
                openInput().use { ins ->
                    if (size == 0L) { sink.write(encryptor.encryptChunk(ByteArray(0), true)) }
                    else {
                        val buf = ByteArray(chunkSize); var remaining = size
                        while (remaining > 0) {
                            val want = minOf(buf.size.toLong(), remaining).toInt()
                            var read = 0
                            while (read < want) { val r = ins.read(buf, read, want - read); if (r < 0) break; read += r }
                            val eof = read < want
                            val last = eof || remaining - read <= 0
                            sink.write(encryptor.encryptChunk(buf.copyOf(read), last))
                            remaining -= read
                            if (eof) break
                        }
                    }
                }
                if (pad > 0) {
                    val rnd = SecureRandom(); val block = ByteArray(64 * 1024); var left = pad
                    while (left > 0) { val n = minOf(block.size.toLong(), left).toInt(); rnd.nextBytes(block); sink.write(block, 0, n); left -= n }
                }
            }
        }
    }
}
