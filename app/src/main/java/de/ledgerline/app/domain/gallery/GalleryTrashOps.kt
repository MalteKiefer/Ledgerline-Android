package de.ledgerline.app.domain.gallery

import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.model.GalleryPhoto

/**
 * Pure manifest transforms for gallery trash management. Each returns a new
 * [GalleryManifest]; unknown ids are safe no-ops. Mirrors the web `vaultGallery`
 * `restore` / `_purgeOne` / `emptyTrash` ops (`resources/js/app.js`).
 *
 * Permanent removal drops the photos AND cleans up dangling references:
 *  - album `photoIds` filtered, and `cover` repointed to the first remaining photo
 *    (or null) when it pointed at a removed photo;
 *  - person `faces` filtered, and a cluster left with fewer than 2 faces is dropped
 *    (matches web: a cluster needs >= 2 faces to survive).
 *
 * The freed blob refs for the removed photos are collected by [freedRefs] so the
 * caller can release them after the manifest write.
 */
object GalleryTrashOps {

    /** All blob refs a photo owns (original, renditions, meta, face crops). */
    fun freedRefs(photo: GalleryPhoto): List<String> =
        (listOfNotNull(
            photo.originalRef,
            photo.thumbRef,
            photo.mediumRef,
            photo.motionRef,
            photo.metaRef,
        ) + photo.faceCropRefs).filter { it.isNotBlank() }

    /** Freed refs for every photo whose id is in [ids] (deduped). */
    fun freedRefs(m: GalleryManifest, ids: Set<String>): List<String> =
        m.photos.filter { it.id in ids }.flatMap { freedRefs(it) }.distinct()

    /** Clear `trashed` on the photos in [ids] (restore from trash). */
    fun restore(m: GalleryManifest, ids: Set<String>): GalleryManifest =
        m.copy(photos = m.photos.map { if (it.id in ids) it.copy(trashed = false) else it })

    /** Permanently remove the photos in [ids] and clean album/person references. */
    fun remove(m: GalleryManifest, ids: Set<String>): GalleryManifest {
        if (ids.isEmpty()) return m
        val photos = m.photos.filterNot { it.id in ids }
        val albums = m.albums.map { album ->
            val remaining = album.photoIds.filterNot { it in ids }
            val cover = if (album.cover in ids) remaining.firstOrNull() else album.cover
            album.copy(photoIds = remaining, cover = cover)
        }
        val people = m.people
            .map { person -> person.copy(faces = person.faces.filterNot { it.photoId in ids }) }
            .filter { it.faces.size >= 2 }
        return m.copy(photos = photos, albums = albums, people = people)
    }

    /** Permanently remove ALL trashed photos and clean references. */
    fun emptyTrash(m: GalleryManifest): GalleryManifest =
        remove(m, m.photos.filter { it.trashed }.map { it.id }.toSet())
}
