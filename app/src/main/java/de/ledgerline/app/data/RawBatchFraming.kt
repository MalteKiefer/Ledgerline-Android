package de.ledgerline.app.data

/**
 * Splits the `POST /{p}/raw-batch` framed concat into per-blob ciphertext. The server frames
 * each returned blob as `u32le(idLen) ++ id ++ u32le(size) ++ ciphertext[size]` and simply omits
 * blobs it doesn't own/have — so the result is keyed by blob id, not by request position.
 *
 * Parsing is defensive: any short/inconsistent frame stops the scan (returning what parsed so
 * far) rather than throwing, so a truncated stream never crashes a prefetch.
 */
object RawBatchFraming {
    fun parse(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var off = 0
        while (off + 4 <= bytes.size) {
            val idLen = u32le(bytes, off); off += 4
            if (idLen <= 0 || off + idLen > bytes.size) break
            val id = String(bytes, off, idLen, Charsets.UTF_8); off += idLen
            if (off + 4 > bytes.size) break
            val size = u32le(bytes, off); off += 4
            if (size < 0 || off + size > bytes.size) break
            out[id] = bytes.copyOfRange(off, off + size); off += size
        }
        return out
    }

    private fun u32le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)
}
