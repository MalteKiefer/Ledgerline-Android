package de.ledgerline.app.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.core.security.LockGuard
import de.ledgerline.app.data.GalleryUploader
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.model.PhotoMetaBlob
import de.ledgerline.app.domain.model.PhotoPlace
import de.ledgerline.app.domain.usecase.GalleryBlobs
import de.ledgerline.app.domain.usecase.GalleryUsage
import de.ledgerline.app.domain.usecase.LoadGallery
import de.ledgerline.app.domain.usecase.MutateGallery
import de.ledgerline.app.ui.workspace.files.UsageInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * A content source for [GalleryViewModel.uploadAll]: name + mime + lazy byte reader.
 * [lat]/[lng] are optional device coordinates for camera-captured photos (where the
 * EXIF strip has no GPS). Picker photos leave them null — the server reads their EXIF.
 */
data class PhotoSource(
    val name: String,
    val mime: String,
    val read: () -> ByteArray,
    val lat: Double? = null,
    val lng: Double? = null,
)

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
    private val galleryUsage: GalleryUsage,
    private val uploader: GalleryUploader,
    private val mutate: MutateGallery,
    private val lockGuard: LockGuard,
    private val vaultKeyHolder: de.ledgerline.app.core.security.VaultKeyHolder,
) : ViewModel() {
    data class Progress(val current: Int, val total: Int)

    private val placeCache = mutableMapOf<String, PhotoPlace?>()
    private val _state = MutableStateFlow(GalleryUi(loading = true))
    val state: StateFlow<GalleryUi> = _state

    private val _usage = MutableStateFlow<UsageInfo?>(null)
    val usage: StateFlow<UsageInfo?> = _usage

    private val _uploadProgress = MutableStateFlow<Progress?>(null)
    val uploadProgress: StateFlow<Progress?> = _uploadProgress

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun clearMessage() { _message.value = null }

    init {
        viewModelScope.launch {
            cache.value.collect { g -> if (g != null) recompute() else _state.value = GalleryUi(loading = true) }
        }
        // This ViewModel is activity-scoped and survives a lock (which wipes the
        // gallery cache). Re-fetch whenever the vault is unlocked and the cache is
        // empty — covers both first open and returning from the lock screen, where
        // init() no longer re-runs. Emits immediately with the current unlock state.
        viewModelScope.launch {
            vaultKeyHolder.unlocked.collect { unlocked ->
                if (unlocked && cache.value.value == null) refresh()
            }
        }
        loadUsage()
    }

    /** Fetch gallery blob usage (used/quota) and publish it; silently ignores failure. */
    fun loadUsage() = viewModelScope.launch {
        galleryUsage.invoke()?.let { (used, quota) -> _usage.value = UsageInfo(used, quota) }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        if (load.invoke() is Outcome.Err) _state.value = _state.value.copy(loading = false, error = true)
        loadUsage()
    }

    /** Arm the lock guard so the system picker launch does not trigger idle-lock. */
    fun armLockSuppression() = lockGuard.armSkipOnce()

    /**
     * Upload each source serially: read bytes, compute sha-256 sig, dedup against
     * already-known sigs, upload, and append the new entry to the gallery index.
     * Progress is published via [uploadProgress]; failures are counted and surfaced
     * as `"upload_failed:N"` in [message]. [loadUsage] is called when the queue drains.
     */
    fun uploadAll(sources: List<PhotoSource>) = viewModelScope.launch {
        val existing = cache.value.value?.manifest?.photos
            ?.mapNotNull { it.sig }
            ?.toMutableSet()
            ?: mutableSetOf()

        _uploadProgress.value = Progress(0, sources.size)
        var done = 0
        var failed = 0

        for (src in sources) {
            val bytes = try {
                src.read()
            } catch (_: Exception) {
                failed++
                done++
                _uploadProgress.value = Progress(done, sources.size)
                continue
            }

            val sig = fileSig(bytes)
            if (sig in existing) {
                // Dedup: already present in the gallery index.
                done++
                _uploadProgress.value = Progress(done, sources.size)
                continue
            }

            when (val up = uploader.upload(src.name, src.mime, sig, bytes, nowIso(), src.lat, src.lng)) {
                is Outcome.Ok -> {
                    mutate.invoke { it.copy(photos = it.photos + up.value) }
                    existing += sig
                }
                is Outcome.Err -> failed++
            }
            done++
            _uploadProgress.value = Progress(done, sources.size)
        }

        _uploadProgress.value = null
        loadUsage()
        if (failed > 0) _message.value = "upload_failed:$failed"
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

    /** Lazily loads and decodes the encrypted meta blob's place. Cached per photo id. Returns null on any failure. */
    suspend fun loadPlace(photo: GalleryPhoto): PhotoPlace? {
        if (placeCache.containsKey(photo.id)) return placeCache[photo.id]
        val ref = photo.metaRef ?: return null
        val key = photo.metaKey ?: return null
        val place = try {
            when (val r = blobs.download(ref, key)) {
                is Outcome.Ok -> {
                    val metaJson = Json { ignoreUnknownKeys = true }
                    metaJson.decodeFromString<PhotoMetaBlob>(String(r.value)).place
                }
                is Outcome.Err -> null
            }
        } catch (_: Exception) {
            null
        }
        placeCache[photo.id] = place
        return place
    }

    fun photoById(id: String) = cache.value.value?.manifest?.photos?.firstOrNull { it.id == id }

    private fun recompute() {
        val photos = cache.value.value?.manifest?.photos.orEmpty()
            .filter { !it.trashed }
            .sortedByDescending { it.created ?: "" }
        _state.value = GalleryUi(false, false, photos)
    }

    /** Duplicate signature, byte-compatible with the web `_fileSig`:
     *  "${size}:${hex(sha256(first1MiB ++ last1MiB))}" (tail empty when size <= 1 MiB). */
    private fun fileSig(bytes: ByteArray): String {
        val cap = 1024 * 1024
        val size = bytes.size
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes, 0, minOf(cap, size))
        if (size > cap) md.update(bytes, size - cap, cap)
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return "$size:$hex"
    }

    private fun nowIso(): String =
        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()
}
