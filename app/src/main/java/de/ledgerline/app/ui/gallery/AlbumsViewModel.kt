package de.ledgerline.app.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.domain.gallery.AlbumOps
import de.ledgerline.app.domain.model.GalleryAlbum
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.usecase.MutateGallery
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    private val cache: GalleryCache,
    private val mutate: MutateGallery,
) : ViewModel() {

    /** Albums from the cache, sorted case-insensitively by name. */
    val albums: StateFlow<List<GalleryAlbum>> = cache.value
        .map { g ->
            g?.manifest?.albums.orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun albumById(id: String): GalleryAlbum? =
        cache.value.value?.manifest?.albums?.firstOrNull { it.id == id }

    /** Non-trashed library photos referenced by the album, in library order. */
    fun albumPhotos(album: GalleryAlbum): List<GalleryPhoto> {
        val set = album.photoIds.toSet()
        return cache.value.value?.manifest?.photos.orEmpty()
            .filter { !it.trashed && it.id in set }
    }

    /** The album's cover photo (matching [GalleryAlbum.cover]) or the first album photo. */
    fun coverPhoto(album: GalleryAlbum): GalleryPhoto? {
        val photos = albumPhotos(album)
        return album.cover?.let { c -> photos.firstOrNull { it.id == c } } ?: photos.firstOrNull()
    }

    fun count(album: GalleryAlbum): Int = albumPhotos(album).size

    fun create(name: String, photoIds: List<String>) = viewModelScope.launch {
        val id = de.ledgerline.app.core.Ids.newId()
        val now = nowIso()
        mutate.invoke { AlbumOps.create(it, id, name, photoIds, now) }
    }

    fun rename(albumId: String, name: String) = viewModelScope.launch {
        mutate.invoke { AlbumOps.rename(it, albumId, name) }
    }

    fun delete(albumId: String) = viewModelScope.launch {
        mutate.invoke { AlbumOps.delete(it, albumId) }
    }

    fun addPhotos(albumId: String, ids: List<String>) = viewModelScope.launch {
        mutate.invoke { AlbumOps.addPhotos(it, albumId, ids) }
    }

    fun removePhoto(albumId: String, photoId: String) = viewModelScope.launch {
        mutate.invoke { AlbumOps.removePhoto(it, albumId, photoId) }
    }

    fun setCover(albumId: String, photoId: String) = viewModelScope.launch {
        mutate.invoke { AlbumOps.setCover(it, albumId, photoId) }
    }

    private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()
}
