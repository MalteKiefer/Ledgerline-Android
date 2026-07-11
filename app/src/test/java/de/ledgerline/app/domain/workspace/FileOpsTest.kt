package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.WorkspaceManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOpsTest {

    private fun manifest() = WorkspaceManifest(
        files = listOf(
            FileEntry(id = "f1", blob = "b1", name = "a.txt"),
            FileEntry(id = "f2", blob = "b2", name = "b.txt", trashed = true),
            FileEntry(id = "f3", blob = "b3", name = "c.txt"),
        ),
    )

    @Test fun trashFile_sets_trashed() {
        val m = FileOps.trashFile(manifest(), "f1")
        assertTrue(m.files.first { it.id == "f1" }.trashed)
    }

    @Test fun restoreFile_clears_trashed() {
        val m = FileOps.restoreFile(manifest(), "f2")
        assertFalse(m.files.first { it.id == "f2" }.trashed)
    }

    @Test fun removeFile_deletes_the_entry() {
        val m = FileOps.removeFile(manifest(), "f2")
        assertEquals(listOf("f1", "f3"), m.files.map { it.id })
    }

    @Test fun emptyTrashFiles_removes_only_trashed() {
        val m = FileOps.emptyTrashFiles(manifest())
        assertEquals(listOf("f1", "f3"), m.files.map { it.id })   // f2 (trashed) gone, live kept
    }

    @Test fun trashFile_unknown_id_is_noop() {
        val m = FileOps.trashFile(manifest(), "nope")
        assertEquals(manifest().files, m.files)
    }

    @Test fun restoreFile_unknown_id_is_noop() {
        val m = FileOps.restoreFile(manifest(), "nope")
        assertEquals(manifest().files, m.files)
    }

    @Test fun removeFile_unknown_id_is_noop() {
        val m = FileOps.removeFile(manifest(), "nope")
        assertEquals(manifest().files, m.files)
    }
}
