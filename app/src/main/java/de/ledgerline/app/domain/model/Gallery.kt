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
    @Serializable(with = FlexibleDoubleSerializer::class) val lat: Double? = null,
    @Serializable(with = FlexibleDoubleSerializer::class) val lng: Double? = null,
    val width: Int? = null, val height: Int? = null, val duration: Double? = null,
    val created: String? = null,
    @Serializable(with = FlexibleTrashedSerializer::class) val trashed: Boolean = false,
    val name: String? = null,
    val mime: String? = null, val size: Long? = null,
    val camera: String? = null,
    val taken_at: String? = null,
    val content_id: String? = null,
    val hasFaces: Int? = null,
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val favorite: Boolean = false,
    val failed: Boolean = false,
    val procError: String? = null,
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
    val phash: Long? = null,
    val exif: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class GalleryAlbum(
    val id: String = "", val name: String = "", val photoIds: List<String> = emptyList(),
    val cover: String? = null, val created: String? = null,
    /** Public share-link state (owner-side); null = not shared. Byte-shape = web `al.share`. */
    val share: de.ledgerline.app.domain.model.ShareInfo? = null,
)

@Serializable
data class PersonFace(
    val photoId: String = "",
    val idx: Int = 0,
    val cropRef: String? = null,
    val cropKey: String? = null,
    val manual: Boolean = false, // a manually-tagged face (web `manual:true`)
)

@Serializable
data class GalleryPerson(
    val id: String = "", val name: String = "", val hidden: Boolean = false,
    val centroid: List<Double> = emptyList(),
    val faces: List<PersonFace> = emptyList(),
    // Link to a workspace Contact (bidirectional; the contact stores personId back).
    val contactId: String? = null,
    val contactName: String? = null,
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

/**
 * A photo-shard descriptor in the v2 sealed gallery root. Each shard is a separate
 * encrypted blob (`/gallery/raw/{ref}`, decrypted with the wrapped key [key]) holding up
 * to 1000 photo records. Introduced server-side to keep the root manifest small.
 */
@Serializable
data class GalleryShard(
    val ref: String = "",
    val key: String = "",
    val hash: String? = null,
    val count: Int = 0,
    val bucket: Int = 0,
)

/**
 * The raw `/gallery/store` root manifest. v2 lists photo [shards] (fetched + decrypted +
 * concatenated into the full photo list); v1 (legacy) inlines [photos]. Albums/people are
 * always inline. Decode-only DTO — the in-memory model is the assembled [GalleryManifest].
 */
@Serializable
data class GalleryRoot(
    val v: Int = 1,
    val suite: Int = 1,
    val shardBits: Int = 0,
    val shards: List<GalleryShard> = emptyList(),
    val photos: List<GalleryPhoto> = emptyList(),
    // v3: albums/people live in content-addressed collection blobs (refs below); v1/v2
    // inline them in these fields. The loader prefers the refs when present.
    val albums: List<GalleryAlbum> = emptyList(),
    val people: List<GalleryPerson> = emptyList(),
    val albumsRef: String? = null, val albumsKey: String? = null, val albumsHash: String? = null,
    val peopleRef: String? = null, val peopleKey: String? = null, val peopleHash: String? = null,
)
