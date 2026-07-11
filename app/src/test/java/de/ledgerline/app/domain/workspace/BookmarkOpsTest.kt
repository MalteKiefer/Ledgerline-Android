package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.Bookmark
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.domain.model.WorkspaceManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkOpsTest {

    private fun manifest(
        bookmarks: List<Bookmark> = emptyList(),
        folders: List<NamedFolder> = emptyList(),
    ) = WorkspaceManifest(bookmarks = bookmarks, bookmarkFolders = folders)

    @Test
    fun addBookmark_appends_with_correct_defaults_and_trims() {
        val out = BookmarkOps.addBookmark(
            manifest(), id = "b1", url = "  https://x.dev  ", title = "  Example  ",
            description = "  a note  ", folderId = "f1", tags = listOf("dev", "ref"),
        )

        assertEquals(1, out.bookmarks.size)
        val b = out.bookmarks.first()
        assertEquals("b1", b.id)
        assertEquals("https://x.dev", b.url)
        assertEquals("Example", b.title)
        assertEquals("a note", b.description)
        assertEquals("f1", b.folderId)
        assertEquals(listOf("dev", "ref"), b.tags)
        assertFalse(b.favorite)
        assertFalse(b.readLater)
        assertFalse(b.trashed)
    }

    @Test
    fun addBookmark_allows_null_folder() {
        val out = BookmarkOps.addBookmark(manifest(), "b1", "https://x", "X", "", null, emptyList())
        assertNull(out.bookmarks.first().folderId)
    }

    @Test
    fun editBookmark_updates_fields_and_trims() {
        val m = manifest(bookmarks = listOf(Bookmark(id = "b1", title = "Old", url = "https://old", folderId = "f1", tags = listOf("old"))))

        val out = BookmarkOps.editBookmark(
            m, id = "b1", url = "  https://new  ", title = "  New  ",
            description = "  desc  ", folderId = "f2", tags = listOf("x", "y"),
        )

        val b = out.bookmarks.first()
        assertEquals("https://new", b.url)
        assertEquals("New", b.title)
        assertEquals("desc", b.description)
        assertEquals("f2", b.folderId)
        assertEquals(listOf("x", "y"), b.tags)
    }

    @Test
    fun toggleFavorite_flips_flag() {
        val m = manifest(bookmarks = listOf(Bookmark(id = "b1", favorite = false)))
        val on = BookmarkOps.toggleFavorite(m, "b1")
        assertTrue(on.bookmarks.first().favorite)
        assertFalse(BookmarkOps.toggleFavorite(on, "b1").bookmarks.first().favorite)
    }

    @Test
    fun toggleReadLater_flips_flag() {
        val m = manifest(bookmarks = listOf(Bookmark(id = "b1", readLater = false)))
        val on = BookmarkOps.toggleReadLater(m, "b1")
        assertTrue(on.bookmarks.first().readLater)
        assertFalse(BookmarkOps.toggleReadLater(on, "b1").bookmarks.first().readLater)
    }

    @Test
    fun trashBookmark_sets_trashed_true() {
        val m = manifest(bookmarks = listOf(Bookmark(id = "b1", trashed = false)))
        assertTrue(BookmarkOps.trashBookmark(m, "b1").bookmarks.first().trashed)
    }

    @Test
    fun restoreBookmark_clears_trashed() {
        val m = manifest(bookmarks = listOf(Bookmark(id = "b1", trashed = true)))
        assertFalse(BookmarkOps.restoreBookmark(m, "b1").bookmarks.first().trashed)
    }

    @Test
    fun removeBookmark_deletes_the_item() {
        val m = manifest(bookmarks = listOf(Bookmark(id = "b1"), Bookmark(id = "b2")))
        assertEquals(listOf("b2"), BookmarkOps.removeBookmark(m, "b1").bookmarks.map { it.id })
    }

    @Test
    fun emptyTrashBookmarks_removes_only_trashed_and_keeps_live() {
        val m = manifest(
            bookmarks = listOf(
                Bookmark(id = "live1"),
                Bookmark(id = "trash1", trashed = true),
                Bookmark(id = "live2"),
                Bookmark(id = "trash2", trashed = true),
            ),
        )
        assertEquals(listOf("live1", "live2"), BookmarkOps.emptyTrashBookmarks(m).bookmarks.map { it.id })
    }

    @Test
    fun addFolder_appends_trimmed_name() {
        val out = BookmarkOps.addFolder(manifest(), "f1", "  Work  ")
        assertEquals(listOf("f1"), out.bookmarkFolders.map { it.id })
        assertEquals("Work", out.bookmarkFolders.first().name)
    }

    @Test
    fun renameFolder_trims_name() {
        val m = manifest(folders = listOf(NamedFolder(id = "f1", name = "Old")))
        assertEquals("New Name", BookmarkOps.renameFolder(m, "f1", "  New Name  ").bookmarkFolders.first().name)
    }

    @Test
    fun deleteFolder_removes_folder_and_orphans_its_bookmarks() {
        val m = manifest(
            bookmarks = listOf(
                Bookmark(id = "b1", folderId = "f1"),
                Bookmark(id = "b2", folderId = "f2"),
                Bookmark(id = "b3", folderId = "f1"),
            ),
            folders = listOf(NamedFolder(id = "f1", name = "A"), NamedFolder(id = "f2", name = "B")),
        )

        val out = BookmarkOps.deleteFolder(m, "f1")

        assertEquals(listOf("f2"), out.bookmarkFolders.map { it.id })
        assertNull(out.bookmarks.first { it.id == "b1" }.folderId) // orphaned
        assertNull(out.bookmarks.first { it.id == "b3" }.folderId) // orphaned
        assertEquals("f2", out.bookmarks.first { it.id == "b2" }.folderId) // untouched
    }

    @Test
    fun unknown_id_is_safe_noop() {
        val m = manifest(
            bookmarks = listOf(Bookmark(id = "b1", title = "T", url = "https://x", folderId = "f1")),
            folders = listOf(NamedFolder(id = "f1", name = "A")),
        )

        assertEquals(m.bookmarks, BookmarkOps.editBookmark(m, "zzz", "https://y", "Y", "", null, emptyList()).bookmarks)
        assertEquals(m.bookmarks, BookmarkOps.toggleFavorite(m, "zzz").bookmarks)
        assertEquals(m.bookmarks, BookmarkOps.toggleReadLater(m, "zzz").bookmarks)
        assertEquals(m.bookmarks, BookmarkOps.trashBookmark(m, "zzz").bookmarks)
        assertEquals(m.bookmarks, BookmarkOps.restoreBookmark(m, "zzz").bookmarks)
        assertEquals(m.bookmarks, BookmarkOps.removeBookmark(m, "zzz").bookmarks)
        assertEquals(m.bookmarkFolders, BookmarkOps.renameFolder(m, "zzz", "X").bookmarkFolders)
        assertEquals(m.bookmarkFolders, BookmarkOps.deleteFolder(m, "zzz").bookmarkFolders)
        assertEquals(m.bookmarks, BookmarkOps.deleteFolder(m, "zzz").bookmarks)
    }
}
