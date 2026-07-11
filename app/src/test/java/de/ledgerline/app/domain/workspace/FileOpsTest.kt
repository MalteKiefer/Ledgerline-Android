package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.FileVersion
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

    // ---- prependVersion (in-app editor save history) ----

    private fun ver(blob: String) = FileVersion(id = "v-$blob", blob = blob)

    @Test fun prependVersion_prepends_old_as_newest() {
        val existing = listOf(ver("b2"), ver("b3"))
        val r = FileOps.prependVersion(existing, ver("b1"), keep = 20)
        assertEquals(listOf("b1", "b2", "b3"), r.versions.map { it.blob })
        assertTrue(r.freedBlobs.isEmpty())
    }

    @Test fun prependVersion_under_cap_frees_nothing() {
        val existing = (1..5).map { ver("old$it") }
        val r = FileOps.prependVersion(existing, ver("new"), keep = 20)
        assertEquals(6, r.versions.size)
        assertTrue(r.freedBlobs.isEmpty())
    }

    @Test fun prependVersion_caps_and_returns_overflow_blobs() {
        // 20 existing + 1 new = 21; keep=20 → oldest (the last existing) evicted.
        val existing = (1..20).map { ver("old$it") }
        val r = FileOps.prependVersion(existing, ver("new"), keep = 20)
        assertEquals(20, r.versions.size)
        assertEquals("new", r.versions.first().blob)
        assertEquals(listOf("old20"), r.freedBlobs)   // oldest overflow freed
    }

    @Test fun prependVersion_evicts_all_overflow_beyond_cap() {
        val existing = (1..25).map { ver("old$it") }
        val r = FileOps.prependVersion(existing, ver("new"), keep = 20)
        assertEquals(20, r.versions.size)
        // 26 total → 6 overflow: old20..old25 (order preserved, newest-first prepend)
        assertEquals(listOf("old20", "old21", "old22", "old23", "old24", "old25"), r.freedBlobs)
    }
}
