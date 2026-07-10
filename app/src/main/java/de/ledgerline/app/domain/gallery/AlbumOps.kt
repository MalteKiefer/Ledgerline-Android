package de.ledgerline.app.domain.gallery

import de.ledgerline.app.domain.model.GalleryAlbum
import de.ledgerline.app.domain.model.GalleryManifest

/**
 * Pure manifest transforms for album management. Each returns a new [GalleryManifest];
 * mutating an unknown album id is a safe no-op. Mirrors the web `vaultGallery` album ops.
 */
object AlbumOps {

    fun create(
        m: GalleryManifest,
        id: String,
        name: String,
        photoIds: List<String>,
        nowIso: String,
    ): GalleryManifest {
        val album = GalleryAlbum(
            id = id,
            name = name.trim(),
            photoIds = photoIds,
            cover = photoIds.firstOrNull(),
            created = nowIso,
        )
        return m.copy(albums = m.albums + album)
    }

    fun rename(m: GalleryManifest, albumId: String, name: String): GalleryManifest =
        update(m, albumId) { it.copy(name = name.trim()) }

    fun delete(m: GalleryManifest, albumId: String): GalleryManifest =
        m.copy(albums = m.albums.filterNot { it.id == albumId })

    fun addPhotos(m: GalleryManifest, albumId: String, ids: List<String>): GalleryManifest =
        update(m, albumId) { album ->
            val existing = album.photoIds.toSet()
            val merged = album.photoIds + ids.filter { it !in existing }.distinct()
            val cover = album.cover ?: merged.firstOrNull()
            album.copy(photoIds = merged, cover = cover)
        }

    fun removePhoto(m: GalleryManifest, albumId: String, photoId: String): GalleryManifest =
        update(m, albumId) { album ->
            val remaining = album.photoIds.filterNot { it == photoId }
            val cover = if (album.cover == photoId) remaining.firstOrNull() else album.cover
            album.copy(photoIds = remaining, cover = cover)
        }

    fun setCover(m: GalleryManifest, albumId: String, photoId: String): GalleryManifest =
        update(m, albumId) { album ->
            if (photoId in album.photoIds) album.copy(cover = photoId) else album
        }

    private inline fun update(
        m: GalleryManifest,
        albumId: String,
        transform: (GalleryAlbum) -> GalleryAlbum,
    ): GalleryManifest =
        m.copy(albums = m.albums.map { if (it.id == albumId) transform(it) else it })
}
