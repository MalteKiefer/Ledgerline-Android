package de.ledgerline.app.data.gallery

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.offline.Connectivity
import de.ledgerline.app.data.remote.GalleryApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.gallery.GalleryData
import de.ledgerline.app.domain.model.gallery.GalleryExif
import de.ledgerline.app.domain.model.gallery.GalleryPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Online-only Gallery data layer (Phase 1). Holds the live timeline snapshot as a [StateFlow] and
 * patches it in memory after each mutation. Bytes stream plaintext over TLS; there is no client
 * crypto. Mirrors the [de.ledgerline.app.data.files.FilesRepository] shape.
 */
@Singleton
class GalleryRepository @Inject constructor(
    private val sessionHolder: SessionHolder,
    private val connectivity: Connectivity,
) {
    private val _data = MutableStateFlow<GalleryData?>(null)
    val data: StateFlow<GalleryData?> = _data.asStateFlow()

    private fun api(): GalleryApi {
        val s = sessionHolder.get() ?: error("no session")
        return NetworkFactory.createGallery(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin)
    }

    fun clear() { _data.value = null }

    private fun cur() = _data.value ?: GalleryData()
    private fun upsert(p: GalleryPhoto) = _data.let {
        val list = cur().photos
        val next = if (list.any { x -> x.id == p.id }) list.map { x -> if (x.id == p.id) p else x } else listOf(p) + list
        _data.value = GalleryData(next)
    }
    private fun remove(id: Int) { _data.value = GalleryData(cur().photos.filterNot { it.id == id }) }
    private fun removeAll(ids: Set<Int>) { _data.value = GalleryData(cur().photos.filterNot { it.id in ids }) }

    /** Pull the timeline (archived hidden by default). Keeps the last snapshot on transient failure. */
    suspend fun load(archived: Boolean = false): Outcome<GalleryData> = withContext(Dispatchers.IO) {
        sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        try {
            val res = api().data(archived = if (archived) true else null)
            when {
                res.code() == 401 -> Outcome.Err(ErrorKind.HTTP)
                !res.isSuccessful -> _data.value?.let { Outcome.Ok(it) } ?: Outcome.Err(ErrorKind.NETWORK)
                else -> { val body = res.body()!!; _data.value = body; Outcome.Ok(body) }
            }
        } catch (e: Exception) {
            _data.value?.let { Outcome.Ok(it) } ?: Outcome.Err(ErrorKind.NETWORK, e)
        }
    }

    suspend fun trash(): List<GalleryPhoto> = withContext(Dispatchers.IO) {
        runCatching { api().trash().takeIf { it.isSuccessful }?.body()?.photos.orEmpty() }.getOrDefault(emptyList())
    }

    /** Archived photos (own request, not stored in the timeline snapshot). */
    suspend fun archivedList(): List<GalleryPhoto> = withContext(Dispatchers.IO) {
        runCatching { api().data(archived = true).takeIf { it.isSuccessful }?.body()?.photos.orEmpty() }.getOrDefault(emptyList())
    }

    /** Archive/unarchive a photo. When archiving, drop it from the live timeline snapshot. */
    suspend fun setArchived(id: Int, value: Boolean): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching { api().archive(id, buildJsonObject { put("archived", value) }).isSuccessful }.getOrDefault(false)
        if (ok && value) remove(id) else if (ok) { /* unarchived → next refresh brings it back */ }
        ok
    }

    suspend fun bulkArchive(ids: List<Int>, value: Boolean): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            api().bulkArchive(buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })); put("archived", value) }).isSuccessful
        }.getOrDefault(false)
        if (ok && value) removeAll(ids.toSet())
        ok
    }

    // ---- Mutations ----
    suspend fun setFavorite(id: Int, value: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            api().favorite(id, buildJsonObject { put("favorite", value) }).takeIf { it.isSuccessful }?.body()?.photo
        }.getOrNull()?.also(::upsert) != null
    }

    /** Non-invasive edit (rotation/mirror/date/place). Sends the current version as the optimistic guard. */
    suspend fun update(id: Int, patch: kotlinx.serialization.json.JsonObject): Boolean = withContext(Dispatchers.IO) {
        val version = cur().photos.firstOrNull { it.id == id }?.version
        val body = if (version != null && "version" !in patch) {
            kotlinx.serialization.json.JsonObject(patch + ("version" to JsonPrimitive(version)))
        } else patch
        runCatching { api().update(id, body).takeIf { it.isSuccessful }?.body()?.photo }.getOrNull()?.also(::upsert) != null
    }

    suspend fun rotate(id: Int, rotation: Int): Boolean = update(id, buildJsonObject { put("rotation", rotation) })

    suspend fun delete(id: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { api().destroy(id).isSuccessful }.getOrDefault(false).also { if (it) remove(id) }
    }

    suspend fun bulkDelete(ids: List<Int>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            api().bulkDestroy(buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }).isSuccessful
        }.getOrDefault(false).also { if (it) removeAll(ids.toSet()) }
    }

    suspend fun restore(id: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { api().restore(id).isSuccessful }.getOrDefault(false)
    }
    suspend fun forceDelete(id: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { api().force(id).isSuccessful }.getOrDefault(false)
    }
    suspend fun emptyTrash(): Boolean = withContext(Dispatchers.IO) {
        runCatching { api().emptyTrash().isSuccessful }.getOrDefault(false)
    }

    // ---- Upload (whole below threshold, chunked above) ----
    suspend fun upload(file: File, name: String, mime: String?): Outcome<GalleryPhoto> {
        if (!connectivity.isOnline()) return Outcome.Err(ErrorKind.NETWORK)
        return if (file.length() >= CHUNK_THRESHOLD) uploadChunked(file, name, mime) else uploadSingle(file, name, mime)
    }

    private suspend fun uploadSingle(file: File, name: String, mime: String?): Outcome<GalleryPhoto> = withContext(Dispatchers.IO) {
        try {
            val body = file.asRequestBody((mime ?: "application/octet-stream").toMediaTypeOrNull())
            val res = api().upload(MultipartBody.Part.createFormData("file", name, body))
            if (res.code() == 413) return@withContext Outcome.Err(ErrorKind.QUOTA)
            res.body()?.photo?.let { upsert(it); Outcome.Ok(it) } ?: Outcome.Err(ErrorKind.NETWORK)
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    private suspend fun uploadChunked(file: File, name: String, mime: String?): Outcome<GalleryPhoto> = withContext(Dispatchers.IO) {
        try {
            val init = api().chunkInit(buildJsonObject { put("name", name); put("size", file.length()) })
            if (init.code() == 413) return@withContext Outcome.Err(ErrorKind.QUOTA)
            val session = init.body() ?: return@withContext Outcome.Err(ErrorKind.NETWORK)
            val partSize = session.partSize.coerceAtLeast(1)
            file.inputStream().use { stream ->
                val buf = ByteArray(partSize.toInt())
                var index = 0
                while (true) {
                    val read = stream.readNBytes(buf, 0, buf.size)
                    if (read <= 0) break
                    val slice = if (read == buf.size) buf else buf.copyOf(read)
                    val part = MultipartBody.Part.createFormData("file", name, slice.toRequestBody((mime ?: "application/octet-stream").toMediaTypeOrNull()))
                    val pr = api().chunkPart(session.id.textPart(), index.toString().textPart(), part)
                    if (!pr.isSuccessful) {
                        runCatching { api().chunkAbort(buildJsonObject { put("id", session.id) }) }
                        return@withContext if (pr.code() == 413) Outcome.Err(ErrorKind.QUOTA) else Outcome.Err(ErrorKind.NETWORK)
                    }
                    index++
                }
            }
            val res = api().chunkComplete(buildJsonObject { put("id", session.id) })
            if (res.code() == 413) return@withContext Outcome.Err(ErrorKind.QUOTA)
            res.body()?.photo?.let { upsert(it); Outcome.Ok(it) } ?: Outcome.Err(ErrorKind.NETWORK)
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    // ---- Bytes ----
    suspend fun thumbBytes(id: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { api().thumb(id).takeIf { it.isSuccessful }?.body()?.bytes() }.getOrNull()
    }
    suspend fun previewBytes(id: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { api().preview(id).takeIf { it.isSuccessful }?.body()?.bytes() }.getOrNull()
    }
    /** Stream a photo's original (or edited) bytes into [dest] for saving/opening. */
    suspend fun downloadToFile(id: Int, dest: File, variant: String? = null): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            api().download(id, variant).takeIf { it.isSuccessful }?.body()?.let { b -> dest.outputStream().use { copyBody(b, it) }; true } ?: false
        }.getOrDefault(false)
    }
    suspend fun exif(id: Int): GalleryExif? = withContext(Dispatchers.IO) {
        runCatching { api().exif(id).takeIf { it.isSuccessful }?.body() }.getOrNull()
    }

    /** Stream a video's playback rendition (or a Live Photo motion clip) into [dest] over the pinned
     *  OkHttp transport, so playback keeps SPKI pinning instead of MediaPlayer's unpinned HTTP stack. */
    suspend fun downloadVideo(id: Int, dest: File, motion: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val res = if (motion) api().motion(id) else api().play(id)
            res.takeIf { it.isSuccessful }?.body()?.let { b -> dest.outputStream().use { copyBody(b, it) }; true } ?: false
        }.getOrDefault(false)
    }

    // ---- Albums ----
    suspend fun albums(): List<de.ledgerline.app.domain.model.gallery.GalleryAlbum> = withContext(Dispatchers.IO) {
        runCatching { api().albums().takeIf { it.isSuccessful }?.body()?.albums.orEmpty() }.getOrDefault(emptyList())
    }
    suspend fun albumPhotos(albumId: Int): List<GalleryPhoto> = withContext(Dispatchers.IO) {
        runCatching { api().data(albumId = albumId).takeIf { it.isSuccessful }?.body()?.photos.orEmpty() }.getOrDefault(emptyList())
    }
    suspend fun createAlbum(name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { api().createAlbum(buildJsonObject { put("name", name.trim()) }).isSuccessful }.getOrDefault(false)
    }
    suspend fun renameAlbum(id: Int, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { api().updateAlbum(id, buildJsonObject { put("name", name.trim()) }).isSuccessful }.getOrDefault(false)
    }
    suspend fun deleteAlbum(id: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { api().deleteAlbum(id).isSuccessful }.getOrDefault(false)
    }
    suspend fun addToAlbum(albumId: Int, ids: List<Int>): Boolean = withContext(Dispatchers.IO) {
        runCatching { api().attachToAlbum(albumId, buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }).isSuccessful }.getOrDefault(false)
    }
    suspend fun removeFromAlbum(albumId: Int, ids: List<Int>): Boolean = withContext(Dispatchers.IO) {
        runCatching { api().detachFromAlbum(albumId, buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }).isSuccessful }.getOrDefault(false)
    }

    private fun copyBody(body: ResponseBody, out: OutputStream) { body.byteStream().use { it.copyTo(out) } }
    private fun String.textPart() = toRequestBody("text/plain".toMediaTypeOrNull())

    companion object { private const val CHUNK_THRESHOLD = 32L * 1024 * 1024 }
}
