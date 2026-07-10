package de.ledgerline.app.domain.gallery

import de.ledgerline.app.domain.model.GalleryAlbum
import de.ledgerline.app.domain.model.GalleryManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumOpsTest {

    private fun manifestWith(vararg albums: GalleryAlbum) =
        GalleryManifest(albums = albums.toList())

    @Test
    fun create_appends_album_with_cover_first_photo() {
        val m = GalleryManifest()

        val out = AlbumOps.create(m, "a1", "  Trip  ", listOf("p1", "p2"), "2026-01-01T00:00:00Z")

        assertEquals(1, out.albums.size)
        val album = out.albums.first()
        assertEquals("a1", album.id)
        assertEquals("Trip", album.name) // trimmed
        assertEquals(listOf("p1", "p2"), album.photoIds)
        assertEquals("p1", album.cover) // cover = first photo
        assertEquals("2026-01-01T00:00:00Z", album.created)
    }

    @Test
    fun create_with_no_photos_has_null_cover() {
        val out = AlbumOps.create(GalleryManifest(), "a1", "Empty", emptyList(), "now")
        assertNull(out.albums.first().cover)
    }

    @Test
    fun addPhotos_unions_dedups_and_preserves_order() {
        val m = manifestWith(GalleryAlbum(id = "a1", name = "A", photoIds = listOf("p1", "p2"), cover = "p1"))

        val out = AlbumOps.addPhotos(m, "a1", listOf("p2", "p3", "p1", "p4"))

        assertEquals(listOf("p1", "p2", "p3", "p4"), out.albums.first().photoIds)
        assertEquals("p1", out.albums.first().cover) // cover unchanged (was set)
    }

    @Test
    fun addPhotos_sets_cover_when_empty() {
        val m = manifestWith(GalleryAlbum(id = "a1", name = "A", photoIds = emptyList(), cover = null))

        val out = AlbumOps.addPhotos(m, "a1", listOf("p5", "p6"))

        assertEquals(listOf("p5", "p6"), out.albums.first().photoIds)
        assertEquals("p5", out.albums.first().cover)
    }

    @Test
    fun removePhoto_drops_id_and_repoints_cover_when_removed() {
        val m = manifestWith(GalleryAlbum(id = "a1", name = "A", photoIds = listOf("p1", "p2", "p3"), cover = "p1"))

        val out = AlbumOps.removePhoto(m, "a1", "p1")

        assertEquals(listOf("p2", "p3"), out.albums.first().photoIds)
        assertEquals("p2", out.albums.first().cover) // repointed to first remaining
    }

    @Test
    fun removePhoto_keeps_cover_when_other_photo_removed() {
        val m = manifestWith(GalleryAlbum(id = "a1", name = "A", photoIds = listOf("p1", "p2"), cover = "p1"))

        val out = AlbumOps.removePhoto(m, "a1", "p2")

        assertEquals(listOf("p1"), out.albums.first().photoIds)
        assertEquals("p1", out.albums.first().cover)
    }

    @Test
    fun removePhoto_sets_cover_null_when_last_removed() {
        val m = manifestWith(GalleryAlbum(id = "a1", name = "A", photoIds = listOf("p1"), cover = "p1"))

        val out = AlbumOps.removePhoto(m, "a1", "p1")

        assertTrue(out.albums.first().photoIds.isEmpty())
        assertNull(out.albums.first().cover)
    }

    @Test
    fun setCover_sets_when_member() {
        val m = manifestWith(GalleryAlbum(id = "a1", name = "A", photoIds = listOf("p1", "p2"), cover = "p1"))

        val out = AlbumOps.setCover(m, "a1", "p2")

        assertEquals("p2", out.albums.first().cover)
    }

    @Test
    fun setCover_ignores_non_member() {
        val album = GalleryAlbum(id = "a1", name = "A", photoIds = listOf("p1", "p2"), cover = "p1")
        val m = manifestWith(album)

        val out = AlbumOps.setCover(m, "a1", "pX")

        assertEquals("p1", out.albums.first().cover) // unchanged
    }

    @Test
    fun rename_trims_name() {
        val m = manifestWith(GalleryAlbum(id = "a1", name = "Old", photoIds = listOf("p1"), cover = "p1"))

        val out = AlbumOps.rename(m, "a1", "  New Name  ")

        assertEquals("New Name", out.albums.first().name)
    }

    @Test
    fun delete_removes_album_by_id() {
        val m = manifestWith(
            GalleryAlbum(id = "a1", name = "A"),
            GalleryAlbum(id = "a2", name = "B"),
        )

        val out = AlbumOps.delete(m, "a1")

        assertEquals(listOf("a2"), out.albums.map { it.id })
    }

    @Test
    fun unknown_albumId_is_safe_noop() {
        val m = manifestWith(GalleryAlbum(id = "a1", name = "A", photoIds = listOf("p1"), cover = "p1"))

        assertEquals(m.albums, AlbumOps.rename(m, "zzz", "X").albums)
        assertEquals(m.albums, AlbumOps.addPhotos(m, "zzz", listOf("p9")).albums)
        assertEquals(m.albums, AlbumOps.removePhoto(m, "zzz", "p1").albums)
        assertEquals(m.albums, AlbumOps.setCover(m, "zzz", "p1").albums)
        assertEquals(m.albums, AlbumOps.delete(m, "zzz").albums)
    }
}
