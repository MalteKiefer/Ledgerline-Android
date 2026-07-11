package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.Bookmark
import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.model.TodoItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSearchTest {

    // ---- Notes: title / content / tags ----

    @Test fun note_matches_title_case_insensitive() {
        val note = Note(title = "Shopping List", content = "milk", tags = listOf("home"))
        assertTrue(WorkspaceSearch.matches(note, "shopping"))
        assertTrue(WorkspaceSearch.matches(note, "SHOP"))
    }

    @Test fun note_matches_content() {
        val note = Note(title = "T", content = "buy some MILK today")
        assertTrue(WorkspaceSearch.matches(note, "milk"))
    }

    @Test fun note_matches_tag() {
        val note = Note(title = "T", content = "c", tags = listOf("Groceries", "Weekend"))
        assertTrue(WorkspaceSearch.matches(note, "weekend"))
    }

    @Test fun note_blank_query_matches_all() {
        val note = Note(title = "anything")
        assertTrue(WorkspaceSearch.matches(note, ""))
        assertTrue(WorkspaceSearch.matches(note, "   "))
    }

    @Test fun note_no_match_returns_false() {
        val note = Note(title = "Shopping", content = "milk", tags = listOf("home"))
        assertFalse(WorkspaceSearch.matches(note, "zzz"))
    }

    // ---- Bookmarks: title / url / description / tags ----

    @Test fun bookmark_matches_each_field() {
        val bm = Bookmark(
            title = "Kotlin Docs",
            url = "https://kotlinlang.org",
            description = "reference material",
            tags = listOf("dev", "lang"),
        )
        assertTrue(WorkspaceSearch.matches(bm, "kotlin"))       // title
        assertTrue(WorkspaceSearch.matches(bm, "kotlinlang"))   // url
        assertTrue(WorkspaceSearch.matches(bm, "REFERENCE"))    // description (case-insensitive)
        assertTrue(WorkspaceSearch.matches(bm, "lang"))         // tag
    }

    @Test fun bookmark_blank_matches_all_and_no_match_false() {
        val bm = Bookmark(title = "A", url = "http://a.test")
        assertTrue(WorkspaceSearch.matches(bm, ""))
        assertFalse(WorkspaceSearch.matches(bm, "nomatch"))
    }

    // ---- Todos: title / description / tags ----

    @Test fun todo_matches_each_field() {
        val td = TodoItem(title = "Call Bob", description = "About the RENT", tags = listOf("urgent"))
        assertTrue(WorkspaceSearch.matches(td, "call"))     // title
        assertTrue(WorkspaceSearch.matches(td, "rent"))     // description case-insensitive
        assertTrue(WorkspaceSearch.matches(td, "URGENT"))   // tag
    }

    @Test fun todo_blank_matches_all_and_no_match_false() {
        val td = TodoItem(title = "X")
        assertTrue(WorkspaceSearch.matches(td, "  "))
        assertFalse(WorkspaceSearch.matches(td, "q"))
    }

    // ---- Files: name only ----

    @Test fun file_matches_name_case_insensitive() {
        val f = FileEntry(name = "Report_Q1.pdf")
        assertTrue(WorkspaceSearch.matches(f, "report"))
        assertTrue(WorkspaceSearch.matches(f, "Q1"))
    }

    @Test fun file_blank_matches_all_and_no_match_false() {
        val f = FileEntry(name = "photo.jpg")
        assertTrue(WorkspaceSearch.matches(f, ""))
        assertFalse(WorkspaceSearch.matches(f, "pdf"))
    }

    // ---- Folders: name only ----

    @Test fun folder_matches_name() {
        val folder = NamedFolder(name = "Invoices")
        assertTrue(WorkspaceSearch.matches(folder, "invoice"))
        assertFalse(WorkspaceSearch.matches(folder, "photos"))
        assertTrue(WorkspaceSearch.matches(folder, ""))
    }
}
