package de.ledgerline.app.data

import java.io.InputStream
import java.security.MessageDigest

/**
 * Windowed content signature `"${size}:${hex(sha256(first1MiB ++ last1MiB))}"` (tail omitted when
 * size <= 1 MiB), byte-compatible with the web `_fileSig`. Reads only up to 2 MiB regardless of file
 * size by (re-)streaming from [openInput]. Used to dedupe import-queue entries (and gallery photos).
 */
object ContentSig {
    fun of(openInput: () -> InputStream, size: Long): String {
        val cap = 1024 * 1024
        val md = MessageDigest.getInstance("SHA-256")
        val head = ByteArray(minOf(cap.toLong(), size).toInt())
        openInput().use { readFully(it, head) }
        md.update(head)
        if (size > cap) {
            val tail = ByteArray(cap)
            openInput().use { ins -> skipFully(ins, size - cap); readFully(ins, tail) }
            md.update(tail)
        }
        return "$size:" + md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readFully(ins: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    private fun skipFully(ins: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = ins.skip(left)
            if (s > 0) { left -= s; continue }
            if (ins.read() < 0) throw java.io.EOFException()
            left--
        }
    }
}
