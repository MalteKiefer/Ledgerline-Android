package de.ledgerline.app.domain.model.gallery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Plaintext-relational Gallery models (Phase 1 — MVP viewing). A photo/video row carries metadata
 * only; bytes stream as sandboxed octet-stream over TLS (thumb/preview/raw). Decoding is lenient
 * (`ignoreUnknownKeys`) so additive server fields (ML/people/album pivots) never break us.
 */
@Serializable
data class GalleryPhoto(
    val id: Int = 0,
    val name: String = "",
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long = 0,
    val favorite: Boolean = false,
    /** WebP thumbnail ready? While false the client shows a spinner and reloads. */
    val thumb: Boolean = false,
    /** Browser-viewable full-size WebP preview ready? (original may be HEIC). */
    val preview: Boolean = false,
    /** Live Photo motion clip attached (served at /gallery/{photo}/motion). */
    val motion: Boolean = false,
    @SerialName("media_type") val mediaType: String = "image", // image | video
    val status: String = "ready",                              // ready | processing | failed
    val duration: Int? = null,                                 // video seconds
    val rotation: Int = 0,                                     // 0|90|180|270 (non-invasive)
    @SerialName("flip_h") val flipH: Boolean = false,
    val archived: Boolean = false,
    @SerialName("taken_at") val takenAt: String? = null,
    val camera: String? = null,
    val place: String? = null,
    val lat: Float? = null,
    val lng: Float? = null,
    val version: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val isVideo: Boolean get() = mediaType == "video"
    /** Sort key: capture date, falling back to upload date. */
    val sortKey: String get() = takenAt ?: createdAt ?: ""
}

/** `GET /gallery/data` and `GET /gallery/trash` both return `{ photos: [...] }`. */
@Serializable
data class GalleryData(
    val photos: List<GalleryPhoto> = emptyList(),
)

/** A gallery album (`/gallery/albums`); [count] is the photo count, [coverPhotoId] an optional cover. */
@Serializable
data class GalleryAlbum(
    val id: Int = 0,
    val name: String = "",
    val count: Int = 0,
    @SerialName("cover_photo_id") val coverPhotoId: Int? = null,
    val version: Int = 0,
)

/** `GET /gallery/{photo}/exif` — photo overview + section-grouped EXIF for the lightbox sidebar. */
@Serializable
data class GalleryExif(
    val id: Int = 0,
    val name: String = "",
    val mime: String? = null,
    val size: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("taken_at") val takenAt: String? = null,
    val camera: String? = null,
    val place: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    /** section → (tag → value). */
    val exif: Map<String, Map<String, String>> = emptyMap(),
)
