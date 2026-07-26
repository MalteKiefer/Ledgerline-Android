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
 *
 * The cache is bounded by an LRU size limit: [put] enforces [OfflineFlags.maxBytes]
 * after each write, and [get] touches the file's last-modified time so read recency
 * (not just write recency) drives eviction.
 */
@Singleton
class BlobDiskCache(
    private val root: File,
    private val offlineFlags: OfflineFlags,
) {

    @Inject
    constructor(@ApplicationContext ctx: Context, offlineFlags: OfflineFlags) :
        this(File(ctx.filesDir, "blobcache"), offlineFlags)

    /** Test-friendly overload: no size limit (unlimited) so existing tests keep compiling. */
    constructor(root: File) : this(root, UnlimitedFlags)

    /** Path-traversal guard: reject empty ids or any containing a separator or `..`. */
    private fun safe(blobId: String): Boolean =
        blobId.isNotEmpty() && !blobId.contains('/') && !blobId.contains('\\') && !blobId.contains("..")

    /**
     * Returns the cached ciphertext for [blobId], or null if absent/invalid/unreadable.
     * On a hit, bumps the file's last-modified time so LRU recency reflects reads.
     * (`System.currentTimeMillis()` is fine here — this is not crypto.)
     */
    fun get(blobId: String): ByteArray? {
        if (!safe(blobId)) return null
        return try {
            val file = File(root, blobId)
            if (!file.exists()) {
                null
            } else {
                val bytes = file.readBytes()
                runCatching { file.setLastModified(System.currentTimeMillis()) }
                bytes
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Atomically writes [ciphertext] for [blobId]: temp file, then rename. No-op if id
     * invalid. After writing, enforces the configured cache size limit (LRU), never
     * evicting the blob just written.
     */
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
        enforceLimit(offlineFlags.maxBytes(), protect = blobId)
    }

    /** A scratch directory (under the cache root) for transient encrypt-to-disk uploads. */
    fun tempDir(): File = File(root, "tmp").apply { mkdirs() }

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

    /**
     * Bounds the cache to [maxBytes] via LRU eviction: while over the limit, deletes the
     * file with the smallest last-modified time (oldest). [maxBytes] `<= 0` is unlimited
     * (no-op). Stops when under the limit or no evictable files remain.
     */
    fun enforceLimit(maxBytes: Long) = enforceLimit(maxBytes, protect = null)

    private fun enforceLimit(maxBytes: Long, protect: String?) {
        if (maxBytes <= 0L) return
        while (sizeBytes() > maxBytes) {
            // Oldest evictable file first; never touch the just-written blob.
            val victim = root.listFiles()
                ?.filter { it.isFile && it.name != protect }
                ?.minByOrNull { it.lastModified() }
                ?: return
            if (!victim.delete()) return
        }
    }

    /** Unlimited flags for the test-only [File]-root constructor. */
    private object UnlimitedFlags : OfflineFlags {
        override fun enabled() = true
        override fun filesPolicy() = de.ledgerline.app.data.offline.FileBlobPolicy.ON_DEMAND
        override fun photosPolicy() = de.ledgerline.app.data.offline.PhotoBlobPolicy.ON_DEMAND
        override fun contactsPolicy() = de.ledgerline.app.data.offline.ContactBlobPolicy.ON_DEMAND
        override fun maxBytes() = 0L
        override fun wifiOnly() = false
        override fun chargingOnly() = false
    }
}
