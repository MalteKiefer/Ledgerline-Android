package de.ledgerline.app.core.offline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores blob **ciphertext** bytes (exactly what `GET .../raw/{blob}` returns) under
 * `filesDir/blobcache/<blobId>`. Blob ids are UUIDs → safe filenames; nonetheless
 * ids are validated defensively so a malformed id can never escape the cache root.
 *
 * Writes are atomic (`<id>.tmp` then rename); reads return null on any error.
 */
@Singleton
class BlobDiskCache(private val root: File) {

    @Inject
    constructor(@ApplicationContext ctx: Context) : this(File(ctx.filesDir, "blobcache"))

    /** Path-traversal guard: reject empty ids or any containing a separator or `..`. */
    private fun safe(blobId: String): Boolean =
        blobId.isNotEmpty() && !blobId.contains('/') && !blobId.contains('\\') && !blobId.contains("..")

    /** Returns the cached ciphertext for [blobId], or null if absent/invalid/unreadable. */
    fun get(blobId: String): ByteArray? {
        if (!safe(blobId)) return null
        return try {
            val file = File(root, blobId)
            if (!file.exists()) null else file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    /** Atomically writes [ciphertext] for [blobId]: temp file, then rename. No-op if id invalid. */
    fun put(blobId: String, ciphertext: ByteArray) {
        if (!safe(blobId)) return
        root.mkdirs()
        val tmp = File(root, "$blobId.tmp")
        val dest = File(root, blobId)
        tmp.writeBytes(ciphertext)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    /** True if a cached blob exists for [blobId]. */
    fun has(blobId: String): Boolean = safe(blobId) && File(root, blobId).exists()

    /** Removes the cached blob for [blobId] (no-op if absent/invalid). */
    fun remove(blobId: String) {
        if (!safe(blobId)) return
        File(root, blobId).delete()
    }

    /** Deletes every file under the cache root. */
    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }

    /** Sum of file sizes under the cache root (0 if none). */
    fun sizeBytes(): Long =
        root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
}
