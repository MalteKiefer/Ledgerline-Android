package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.model.WorkspaceManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteOpsTest {

    private fun manifest(notes: List<Note> = emptyList()) = WorkspaceManifest(notes = notes)

    @Test
    fun addNote_appends_with_defaults_and_updated_set() {
        val out = NoteOps.addNote(manifest(), id = "n1", nowIso = "2026-07-11T00:00:00Z")

        assertEquals(1, out.notes.size)
        val n = out.notes.first()
        assertEquals("n1", n.id)
        assertEquals("", n.title)
        assertEquals("", n.content)
        assertFalse(n.pinned)
        assertFalse(n.trashed)
        assertEquals("2026-07-11T00:00:00Z", n.updated)
    }

    @Test
    fun addNote_preserves_existing_notes() {
        val m = manifest(notes = listOf(Note(id = "old", title = "Old")))
        val out = NoteOps.addNote(m, "n1", "2026-07-11T00:00:00Z")
        assertEquals(listOf("old", "n1"), out.notes.map { it.id })
    }

    @Test
    fun updateNote_sets_trimmed_title_content_and_updated() {
        val m = manifest(notes = listOf(Note(id = "n1", title = "Old", content = "Old body", updated = "2026-01-01T00:00:00Z")))

        val out = NoteOps.updateNote(m, "n1", title = "  New title  ", content = "New body", nowIso = "2026-07-11T12:00:00Z")

        val n = out.notes.first()
        assertEquals("New title", n.title)
        assertEquals("New body", n.content)
        assertEquals("2026-07-11T12:00:00Z", n.updated)
    }

    @Test
    fun updateNote_keeps_content_whitespace_intact() {
        val m = manifest(notes = listOf(Note(id = "n1")))
        val out = NoteOps.updateNote(m, "n1", "T", "  leading and trailing  \n", "2026-07-11T00:00:00Z")
        assertEquals("  leading and trailing  \n", out.notes.first().content)
    }

    @Test
    fun togglePin_flips() {
        val m = manifest(notes = listOf(Note(id = "n1", pinned = false)))
        val pinned = NoteOps.togglePin(m, "n1")
        assertTrue(pinned.notes.first().pinned)
        val unpinned = NoteOps.togglePin(pinned, "n1")
        assertFalse(unpinned.notes.first().pinned)
    }

    @Test
    fun trashNote_sets_trashed() {
        val m = manifest(notes = listOf(Note(id = "n1")))
        val out = NoteOps.trashNote(m, "n1")
        assertTrue(out.notes.first().trashed)
    }

    @Test
    fun unknown_id_is_a_safe_no_op() {
        val m = manifest(notes = listOf(Note(id = "n1", title = "Keep", pinned = true)))

        assertEquals(m, NoteOps.updateNote(m, "nope", "X", "Y", "2026-07-11T00:00:00Z"))
        assertEquals(m, NoteOps.togglePin(m, "nope"))
        assertEquals(m, NoteOps.trashNote(m, "nope"))
    }

    @Test
    fun upsert_appends_when_absent_and_updates_when_present() {
        val m = manifest(notes = emptyList())
        val added = NoteOps.upsertNote(m, "new", "  Title  ", "body", "2026-07-11T00:00:00Z")
        assertEquals(1, added.notes.size)
        assertEquals("Title", added.notes.first().title)   // trimmed
        assertEquals("body", added.notes.first().content)

        val updated = NoteOps.upsertNote(added, "new", "Title2", "body2", "2026-07-11T01:00:00Z")
        assertEquals(1, updated.notes.size)                // no duplicate
        assertEquals("Title2", updated.notes.first().title)
        assertEquals("body2", updated.notes.first().content)
    }
}
