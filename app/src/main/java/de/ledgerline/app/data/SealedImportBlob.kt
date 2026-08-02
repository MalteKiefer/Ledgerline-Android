package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.Crypto
import okio.buffer
import okio.sink
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Encrypts an import's plaintext source bytes to a **VK-sealed file** on disk (so a queued offline
 * import keeps NO plaintext at rest, honoring the zero-knowledge contract) and reads it back as a
 * lazily-decrypting [InputStream] (constant memory — a multi-GB video is never fully buffered).
 *
 * The on-disk format is exactly the blob-upload format (secretstream: header ++ framed chunks ++
 * Padmé tail), so [SealedInputStream] reuses the same [Crypto.ContentDecryptor] framing and stops
 * at TAG_FINAL, ignoring the padding tail — identical to [BlobDownloader].
 */
object SealedImportBlob {

    /** Stream-encrypt [openInput] ([size] bytes) into [file]; returns the sealed content key (`{c,n}`). */
    fun sealToFile(crypto: Crypto, vk: ByteArray, size: Long, openInput: () -> InputStream, file: File): String {
        val enc = crypto.newContentEncryptor(vk)
        val body = EncryptedUpload.body(enc, crypto.contentChunkSize, size, openInput)
        file.sink().buffer().use { sink -> body.writeTo(sink) }
        return enc.sealKey() // must be read AFTER writeTo (per EncryptedUpload contract)
    }

    /** A fresh decrypting stream over a sealed file — safe to open more than once (sig, then upload). */
    fun open(crypto: Crypto, vk: ByteArray, encFileKey: String, file: File): InputStream =
        SealedInputStream(crypto, crypto.contentDecryptor(encFileKey, vk), file)
}

/** Reads a sealed blob file, decrypting secretstream frames on demand into a plaintext stream. */
private class SealedInputStream(
    private val crypto: Crypto,
    private val dec: Crypto.ContentDecryptor,
    file: File,
) : InputStream() {
    private val raw = BufferedInputStream(FileInputStream(file))
    private var buf = ByteArray(0)
    private var pos = 0
    private var done = false
    private var started = false

    private fun ensureStarted() {
        if (started) return
        val header = ByteArray(dec.headerBytes)
        readFully(header)
        dec.start(header)
        started = true
    }

    /** Load the next plaintext frame into [buf]; false at final/EOF/Padmé. */
    private fun fill(): Boolean {
        if (done) return false
        ensureStarted()
        val lenB = ByteArray(4)
        if (!readUpTo(lenB)) { done = true; return false }        // <4 bytes → padding/none
        val len = crypto.readU32le(lenB, 0)
        if (len <= 0) { done = true; return false }               // Padmé tail
        val frame = ByteArray(len)
        readFully(frame)
        val (msg, final) = dec.decryptFrame(frame)
        buf = msg; pos = 0
        if (final) done = true
        return true
    }

    override fun read(): Int {
        while (pos >= buf.size) { if (!fill()) return -1 }
        return buf[pos++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= buf.size) { if (!fill()) return -1 }
        val n = minOf(len, buf.size - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() = raw.close()

    private fun readFully(dst: ByteArray) {
        var o = 0
        while (o < dst.size) {
            val r = raw.read(dst, o, dst.size - o)
            if (r < 0) throw java.io.EOFException("sealed import blob truncated")
            o += r
        }
    }

    /** Read exactly [dst].size bytes; return false if fewer are available (clean EOF). */
    private fun readUpTo(dst: ByteArray): Boolean {
        var o = 0
        while (o < dst.size) {
            val r = raw.read(dst, o, dst.size - o)
            if (r < 0) return false
            o += r
        }
        return true
    }
}
