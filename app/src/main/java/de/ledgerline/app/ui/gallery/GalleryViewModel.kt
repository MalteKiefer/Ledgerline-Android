package de.ledgerline.app.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.usecase.GalleryBlobs
import de.ledgerline.app.domain.usecase.LoadGallery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GalleryUi(
    val loading: Boolean = false,
    val error: Boolean = false,
    val photos: List<GalleryPhoto> = emptyList(),
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val load: LoadGallery,
    private val cache: GalleryCache,
    private val blobs: GalleryBlobs,
    private val thumbs: ThumbCache,
) : ViewModel() {
    private val _state = MutableStateFlow(GalleryUi(loading = true))
    val state: StateFlow<GalleryUi> = _state

    init {
        viewModelScope.launch {
            cache.value.collect { g -> if (g != null) recompute() else _state.value = GalleryUi(loading = true) }
        }
        if (cache.value.value == null) refresh()
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        if (load.invoke() is Outcome.Err) _state.value = _state.value.copy(loading = false, error = true)
    }

    /** Returns a cached thumbnail bitmap or downloads+decodes it (cached). Null on failure. */
    suspend fun thumb(photo: GalleryPhoto): Bitmap? {
        thumbs.get(photo.id)?.let { return it }
        val ref = photo.thumbRef ?: return null
        val key = photo.thumbKey ?: return null
        return when (val r = blobs.download(ref, key)) {
            is Outcome.Ok -> BitmapFactory.decodeByteArray(r.value, 0, r.value.size)?.also { thumbs.put(photo.id, it) }
            is Outcome.Err -> null
        }
    }

    suspend fun downloadBytes(ref: String, key: String): Outcome<ByteArray> = blobs.download(ref, key)

    fun photoById(id: String) = cache.value.value?.manifest?.photos?.firstOrNull { it.id == id }

    private fun recompute() {
        val photos = cache.value.value?.manifest?.photos.orEmpty()
            .filter { !it.trashed }
            .sortedByDescending { it.created ?: "" }
        _state.value = GalleryUi(false, false, photos)
    }
}
