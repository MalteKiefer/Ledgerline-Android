package de.ledgerline.app.domain.gallery

import de.ledgerline.app.domain.model.GalleryAlbum
import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.model.GalleryPerson
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.model.PersonFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTrashOpsTest {

    private fun photo(id: String, trashed: Boolean = false) = GalleryPhoto(id = id, trashed = trashed)

    @Test fun restore_clears_trashed_flag() {
        val m = GalleryManifest(photos = listOf(photo("a", trashed = true), photo("b", trashed = true)))
        val out = GalleryTrashOps.restore(m, setOf("a"))
        assertFalse(out.photos.first { it.id == "a" }.trashed)
        assertTrue(out.photos.first { it.id == "b" }.trashed)
    }

    @Test fun freed_refs_collects_all_blob_refs() {
        val p = GalleryPhoto(
            id = "p", originalRef = "o", thumbRef = "t", mediumRef = "med",
            motionRef = "mo", metaRef = "meta", faceCropRefs = listOf("f1", "f2"),
        )
        assertEquals(listOf("o", "t", "med", "mo", "meta", "f1", "f2"), GalleryTrashOps.freedRefs(p))
    }

    @Test fun remove_drops_photos_and_cleans_album_refs() {
        val m = GalleryManifest(
            photos = listOf(photo("a"), photo("b"), photo("c")),
            albums = listOf(
                GalleryAlbum(id = "al", name = "A", photoIds = listOf("a", "b", "c"), cover = "a"),
            ),
        )
        val out = GalleryTrashOps.remove(m, setOf("a"))
        assertEquals(listOf("b", "c"), out.photos.map { it.id })
        val album = out.albums.first()
        assertEquals(listOf("b", "c"), album.photoIds)
        // Cover pointed at the removed photo → repointed to first remaining.
        assertEquals("b", album.cover)
    }

    @Test fun remove_repoints_cover_to_null_when_album_empties() {
        val m = GalleryManifest(
            photos = listOf(photo("a")),
            albums = listOf(GalleryAlbum(id = "al", name = "A", photoIds = listOf("a"), cover = "a")),
        )
        val out = GalleryTrashOps.remove(m, setOf("a"))
        assertTrue(out.albums.first().photoIds.isEmpty())
        assertNull(out.albums.first().cover)
    }

    @Test fun remove_cleans_person_faces_and_drops_small_clusters() {
        val m = GalleryManifest(
            photos = listOf(photo("a"), photo("b"), photo("c")),
            people = listOf(
                // 3 faces → after removing "a" still has 2 → survives.
                GalleryPerson(id = "p1", faces = listOf(
                    PersonFace(photoId = "a"), PersonFace(photoId = "b"), PersonFace(photoId = "c"),
                )),
                // 2 faces → after removing "a" has 1 → dropped.
                GalleryPerson(id = "p2", faces = listOf(
                    PersonFace(photoId = "a"), PersonFace(photoId = "b"),
                )),
            ),
        )
        val out = GalleryTrashOps.remove(m, setOf("a"))
        assertEquals(listOf("p1"), out.people.map { it.id })
        assertEquals(listOf("b", "c"), out.people.first().faces.map { it.photoId })
    }

    @Test fun empty_trash_removes_only_trashed() {
        val m = GalleryManifest(photos = listOf(photo("keep"), photo("gone", trashed = true)))
        val out = GalleryTrashOps.emptyTrash(m)
        assertEquals(listOf("keep"), out.photos.map { it.id })
    }

    @Test fun empty_manifest_no_op() {
        val m = GalleryManifest()
        assertEquals(0, GalleryTrashOps.remove(m, setOf("x")).photos.size)
    }
}
