package de.ledgerline.app.ui.gallery

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.data.gallery.GalleryRepository
import de.ledgerline.app.domain.model.gallery.GalleryData
import de.ledgerline.app.domain.model.gallery.GalleryExif
import de.ledgerline.app.domain.model.gallery.GalleryPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Shell-scoped state for the Gallery tab (Phase 1): timeline snapshot, thumbnails, and mutations. */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: GalleryRepository,
) : ViewModel() {

    val data: StateFlow<GalleryData?> = repo.data

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        repo.load()
        _loading.value = false
        _refreshing.value = false
    }
    fun pullRefresh() { _refreshing.value = true; refresh() }

    /** Live, non-archived photos newest-first by capture date. */
    fun timeline(data: GalleryData?): List<GalleryPhoto> =
        data?.photos.orEmpty().filterNot { it.archived }.sortedByDescending { it.sortKey }

    // ---- Decoded byte caches (id+version keyed) ----
    private val thumbCache = mutableMapOf<String, ImageBitmap?>()
    private val previewCache = mutableMapOf<String, ImageBitmap?>()

    suspend fun thumbnail(p: GalleryPhoto): ImageBitmap? {
        if (!p.thumb) return null // worker still generating; caller shows a spinner + reloads
        val key = "${p.id}:${p.version}"
        thumbCache[key]?.let { return it }
        if (thumbCache.containsKey(key)) return null
        val bmp = repo.thumbBytes(p.id)?.let { decode(it) }
        thumbCache[key] = bmp
        return bmp
    }

    suspend fun preview(p: GalleryPhoto): ImageBitmap? {
        val key = "${p.id}:${p.version}"
        previewCache[key]?.let { return it }
        if (previewCache.containsKey(key)) return null
        // The lightbox WebP; on a cache miss the server 404s and re-queues generation.
        val bmp = repo.previewBytes(p.id)?.let { decode(it) }
        previewCache[key] = bmp
        return bmp
    }

    /** Decoded thumbnail fetched directly by id (album covers, where no row object is at hand). */
    suspend fun thumbById(id: Int?): ImageBitmap? {
        if (id == null) return null
        val key = "cover:$id"
        thumbCache[key]?.let { return it }
        if (thumbCache.containsKey(key)) return null
        val bmp = repo.thumbBytes(id)?.let { decode(it) }
        thumbCache[key] = bmp
        return bmp
    }

    private suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
    }

    // ---- Mutations ----
    fun setFavorite(id: Int, value: Boolean) = viewModelScope.launch { repo.setFavorite(id, value) }
    fun rotate(id: Int, current: Int) = viewModelScope.launch { repo.rotate(id, (current + 90) % 360) }
    fun delete(id: Int, done: () -> Unit = {}) = viewModelScope.launch { if (repo.delete(id)) done() }
    fun bulkDelete(ids: List<Int>, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.bulkDelete(ids)) }

    fun upload(file: File, name: String, mime: String?, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(repo.upload(file, name, mime) is Outcome.Ok) }

    // ---- Archive ----
    fun archive(id: Int, value: Boolean, done: () -> Unit = {}) = viewModelScope.launch { if (repo.setArchived(id, value)) done() }
    fun bulkArchive(ids: List<Int>, value: Boolean, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.bulkArchive(ids, value)) }
    suspend fun loadArchived(): List<GalleryPhoto> = repo.archivedList()

    suspend fun exif(id: Int): GalleryExif? = repo.exif(id)

    /** Download a video (or Live Photo motion clip) to cache for inline playback. Null on failure. */
    suspend fun videoToCache(p: GalleryPhoto, motion: Boolean = false): File? {
        val dir = File(context.cacheDir, "gallery").apply { mkdirs() }
        val dest = File(dir, (if (motion) "motion_" else "play_") + "${p.id}.mp4")
        return if (repo.downloadVideo(p.id, dest, motion)) dest else null
    }

    /** Download original bytes to a private cache file for external open/share. */
    suspend fun downloadToCache(p: GalleryPhoto): File? {
        val dir = File(context.cacheDir, "gallery").apply { mkdirs() }
        val safe = p.name.ifBlank { "photo_${p.id}" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(dir, "${p.id}_$safe")
        return if (repo.downloadToFile(p.id, dest)) dest else null
    }
    suspend fun saveTo(p: GalleryPhoto, out: java.io.OutputStream): Boolean = withContext(Dispatchers.IO) {
        val tmp = downloadToCache(p) ?: return@withContext false
        runCatching { tmp.inputStream().use { it.copyTo(out) }; true }.getOrDefault(false)
    }

    // ---- Albums ----
    suspend fun albums() = repo.albums()
    suspend fun albumPhotos(albumId: Int): List<GalleryPhoto> = repo.albumPhotos(albumId)
    fun createAlbum(name: String, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.createAlbum(name)) }
    fun renameAlbum(id: Int, name: String, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.renameAlbum(id, name)) }
    fun deleteAlbum(id: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.deleteAlbum(id)) }
    fun addToAlbum(albumId: Int, ids: List<Int>, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.addToAlbum(albumId, ids)) }
    fun removeFromAlbum(albumId: Int, ids: List<Int>, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.removeFromAlbum(albumId, ids)) }

    // ---- Trash ----
    suspend fun loadTrash(): List<GalleryPhoto> = repo.trash()
    fun restore(id: Int, done: () -> Unit) = viewModelScope.launch { if (repo.restore(id)) done() }
    fun force(id: Int, done: () -> Unit) = viewModelScope.launch { if (repo.forceDelete(id)) done() }
    fun emptyTrash(done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.emptyTrash()) }

    fun clear() = repo.clear()
}
