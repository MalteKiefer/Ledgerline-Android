package de.ledgerline.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.core.Ids
import de.ledgerline.app.core.crypto.Crypto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable, VK-sealed queue of photo/file imports whose blob upload couldn't complete (offline or a
 * recoverable server error). The source plaintext is stream-encrypted to a per-item sealed file
 * ([SealedImportBlob]); the index (names/mime/geo/folder + each item's sealed content key) is itself
 * VK-sealed. Nothing is stored in the clear. [PendingImportRepository] replays the queue on reconnect,
 * running the full upload+append pipeline from the sealed source and removing each item on success.
 *
 * Dedupe is by content [sig] (the same windowed hash the importer uses), so a retry — or the camera-
 * roll backup re-scanning the same photo — never queues or uploads it twice.
 */
@Singleton
class ImportQueue(
    private val root: File,
    private val crypto: Crypto,
) {
    @Inject constructor(@ApplicationContext ctx: Context, crypto: Crypto) : this(File(ctx.filesDir, "import_queue"), crypto)

    private val indexFile = File(root, "index.enc")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    enum class Kind { PHOTO, FILE }

    @Serializable
    data class Item(
        val id: String,
        val kind: String, // Kind.name
        val name: String,
        val mime: String,
        val size: Long,
        val encFileKey: String,
        val sig: String,
        val lat: Double? = null,
        val lng: Double? = null,
        val folder: String? = null,
    )

    @Serializable
    private data class Index(val items: List<Item> = emptyList())

    /** A queued item plus a re-openable decrypting stream over its sealed source. */
    class Handle(val item: Item, val open: () -> InputStream)

    /** Cheap, VK-free check (used to decide whether a sync pass needs to drain the queue). */
    fun hasPending(): Boolean = (root.listFiles { f -> f.extension == "blob" }?.isNotEmpty()) == true

    suspend fun enqueuePhoto(
        vk: ByteArray, name: String, mime: String, size: Long, sig: String,
        openInput: () -> InputStream, lat: Double?, lng: Double?,
    ): Boolean = enqueue(vk, Kind.PHOTO, name, mime, size, sig, openInput, lat, lng, folder = null)

    suspend fun enqueueFile(
        vk: ByteArray, name: String, mime: String, size: Long, sig: String,
        openInput: () -> InputStream, folder: String?,
    ): Boolean = enqueue(vk, Kind.FILE, name, mime, size, sig, openInput, lat = null, lng = null, folder = folder)

    private suspend fun enqueue(
        vk: ByteArray, kind: Kind, name: String, mime: String, size: Long, sig: String,
        openInput: () -> InputStream, lat: Double?, lng: Double?, folder: String?,
    ): Boolean = mutex.withLock {
        try {
            val idx = readIndex(vk)
            if (idx.items.any { it.sig == sig }) return@withLock true // already queued — dedupe
            root.mkdirs()
            val id = Ids.newId()
            val blob = File(root, "$id.blob")
            val key = SealedImportBlob.sealToFile(crypto, vk, size, openInput, blob)
            writeIndex(vk, Index(idx.items + Item(id, kind.name, name, mime, size, key, sig, lat, lng, folder)))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** All queued items with a decrypting-stream opener over their sealed source. Empty if none/locked. */
    suspend fun pending(vk: ByteArray): List<Handle> = mutex.withLock {
        readIndex(vk).items.mapNotNull { item ->
            val f = File(root, "${item.id}.blob")
            if (!f.exists()) null
            else Handle(item) { SealedImportBlob.open(crypto, vk, item.encFileKey, f) }
        }
    }

    /** Remove a queued item (sealed blob + index entry) after a successful replay. */
    suspend fun remove(vk: ByteArray, id: String) = mutex.withLock {
        try {
            File(root, "$id.blob").delete()
            writeIndex(vk, Index(readIndex(vk).items.filterNot { it.id == id }))
        } catch (_: Exception) { /* best-effort */ }
    }

    /** Erase the whole queue (forced logout / wipe). */
    fun clearAll() {
        try { root.deleteRecursively() } catch (_: Exception) {}
    }

    private fun readIndex(vk: ByteArray): Index {
        if (!indexFile.exists()) return Index()
        return try {
            val plain = crypto.openManifest(indexFile.readText(), vk) ?: return Index()
            json.decodeFromString(Index.serializer(), plain)
        } catch (_: Exception) { Index() }
    }

    private fun writeIndex(vk: ByteArray, idx: Index) {
        root.mkdirs()
        val ct = crypto.sealManifest(json.encodeToString(Index.serializer(), idx), vk)
        val tmp = File(root, "index.enc.tmp")
        tmp.writeText(ct)
        tmp.renameTo(indexFile)
    }
}
