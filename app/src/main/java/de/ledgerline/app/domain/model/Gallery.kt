package de.ledgerline.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class GalleryPhoto(
    val id: String = "",
    val media_type: String = "image",
    val originalRef: String? = null, val originalKey: String? = null,
    val thumbRef: String? = null, val thumbKey: String? = null,
    val mediumRef: String? = null, val mediumKey: String? = null,
    val motionRef: String? = null, val motionKey: String? = null,
    val metaRef: String? = null, val metaKey: String? = null,
    val faceCropRefs: List<String> = emptyList(),
    val sig: String? = null,
    val lat: Double? = null, val lng: Double? = null,
    val width: Int? = null, val height: Int? = null, val duration: Double? = null,
    val created: String? = null, val trashed: Boolean = false,
    val name: String? = null,
    val mime: String? = null, val size: Long? = null,
    val camera: String? = null,
    val taken_at: String? = null,
    val content_id: String? = null,
    val hasFaces: Int? = null,
)

@Serializable
data class PhotoPlace(
    val name: String? = null,
    val display: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
)

@Serializable
data class MetaFace(
    val embedding: List<Double> = emptyList(),
    val cropRef: String? = null,
    val cropKey: String? = null,
)

@Serializable
data class PhotoMetaBlob(
    val place: PhotoPlace? = null,
    val embedding: List<Double> = emptyList(),
    val faces: List<MetaFace> = emptyList(),
)

@Serializable
data class GalleryAlbum(
    val id: String = "", val name: String = "", val photoIds: List<String> = emptyList(),
    val cover: String? = null, val created: String? = null,
)

@Serializable
data class PersonFace(
    val photoId: String = "",
    val idx: Int = 0,
    val cropRef: String? = null,
    val cropKey: String? = null,
)

@Serializable
data class GalleryPerson(
    val id: String = "", val name: String = "", val hidden: Boolean = false,
    val centroid: List<Double> = emptyList(),
    val faces: List<PersonFace> = emptyList(),
)

@Serializable
data class GalleryManifest(
    val v: Int = 1,
    val photos: List<GalleryPhoto> = emptyList(),
    val albums: List<GalleryAlbum> = emptyList(),
    val people: List<GalleryPerson> = emptyList(),
)

/** Decrypted gallery index + server version (for later 4b writes). */
data class Gallery(val manifest: GalleryManifest, val version: Int)
