package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.FileVersion
import de.ledgerline.app.domain.model.NamedFolder
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

    // ---- Move (file / folder) ----

    private fun treeManifest() = WorkspaceManifest(
        files = listOf(
            FileEntry(id = "f1", blob = "b1", name = "a.txt", folder = null),
            FileEntry(id = "f2", blob = "b2", name = "b.txt", folder = "root"),
        ),
        // root ⊃ child ⊃ grand ; sibling is separate
        fileFolders = listOf(
            NamedFolder(id = "root", name = "Root", parent = null),
            NamedFolder(id = "child", name = "Child", parent = "root"),
            NamedFolder(id = "grand", name = "Grand", parent = "child"),
            NamedFolder(id = "sibling", name = "Sibling", parent = null),
        ),
    )

    @Test fun moveFile_sets_folder() {
        val m = FileOps.moveFile(treeManifest(), "f1", "child")
        assertEquals("child", m.files.first { it.id == "f1" }.folder)
    }

    @Test fun moveFile_to_root_clears_folder() {
        val m = FileOps.moveFile(treeManifest(), "f2", null)
        assertEquals(null, m.files.first { it.id == "f2" }.folder)
    }

    @Test fun moveFile_unknown_id_is_noop() {
        val m = FileOps.moveFile(treeManifest(), "nope", "child")
        assertEquals(treeManifest().files, m.files)
    }

    @Test fun moveFolder_sets_parent() {
        val m = FileOps.moveFolder(treeManifest(), "sibling", "root")
        assertEquals("root", m.fileFolders.first { it.id == "sibling" }.parent)
    }

    @Test fun moveFolder_to_root_clears_parent() {
        val m = FileOps.moveFolder(treeManifest(), "child", null)
        assertEquals(null, m.fileFolders.first { it.id == "child" }.parent)
    }

    @Test fun moveFolder_into_itself_is_noop() {
        val m = FileOps.moveFolder(treeManifest(), "root", "root")
        assertEquals(treeManifest().fileFolders, m.fileFolders)
    }

    @Test fun moveFolder_into_own_descendant_is_noop() {
        // root → grand (a descendant of root) must be rejected.
        val m = FileOps.moveFolder(treeManifest(), "root", "grand")
        assertEquals(treeManifest().fileFolders, m.fileFolders)
    }

    @Test fun moveFolder_unknown_id_is_noop() {
        val m = FileOps.moveFolder(treeManifest(), "nope", "root")
        assertEquals(treeManifest().fileFolders, m.fileFolders)
    }

    @Test fun isDescendant_true_for_self() {
        assertTrue(FileOps.isDescendant(treeManifest(), "root", "root"))
    }

    @Test fun isDescendant_true_for_deep_descendant() {
        assertTrue(FileOps.isDescendant(treeManifest(), "root", "grand"))
    }

    @Test fun isDescendant_false_for_sibling() {
        assertFalse(FileOps.isDescendant(treeManifest(), "child", "sibling"))
    }

    @Test fun isDescendant_false_for_ancestor() {
        // child is NOT a descendant of grand (it's the ancestor)
        assertFalse(FileOps.isDescendant(treeManifest(), "grand", "child"))
    }

    // ---- restoreVersion ----

    private fun entryWithVersions() = FileEntry(
        id = "f1", blob = "cur", encFileKey = "curKey", size = 100, mime = "text/plain", name = "cur.txt",
        versions = listOf(
            FileVersion(id = "vA", blob = "bA", encFileKey = "kA", size = 10, mime = "text/plain", name = "a.txt"),
            FileVersion(id = "vB", blob = "bB", encFileKey = "kB", size = 20, mime = "text/plain", name = "b.txt"),
        ),
    )

    @Test fun restoreVersion_swaps_blob_and_snapshots_current() {
        val r = FileOps.restoreVersion(entryWithVersions(), "vA", nowIso = "2026-07-11T00:00:00Z")
        // entry now points at the restored version's blob
        assertEquals("bA", r.entry.blob)
        assertEquals("kA", r.entry.encFileKey)
        assertEquals(10L, r.entry.size)
        // the restored version is gone from history; the old current ("cur") is snapshotted on top
        assertEquals("cur", r.entry.versions.first().blob)
        assertEquals(listOf("cur", "bB"), r.entry.versions.map { it.blob })
        assertTrue(r.freedBlobs.isEmpty())
    }

    @Test fun restoreVersion_drops_the_restored_version() {
        val r = FileOps.restoreVersion(entryWithVersions(), "vB", nowIso = "2026-07-11T00:00:00Z")
        assertFalse(r.entry.versions.any { it.id == "vB" })
        assertEquals(listOf("cur", "bA"), r.entry.versions.map { it.blob })
    }

    @Test fun restoreVersion_frees_overflow_beyond_cap() {
        // Restore v1 (dropped), remaining=[v2], snapshot(cur) prepended → [cur, v2]; keep=1 →
        // v2 overflows and its blob is freed. The snapshot of the current blob is kept.
        val big = entryWithVersions().copy(
            versions = listOf(
                FileVersion(id = "v1", blob = "b1"),
                FileVersion(id = "v2", blob = "b2"),
            ),
        )
        val r = FileOps.restoreVersion(big, "v1", nowIso = "t", keep = 1)
        assertEquals(1, r.entry.versions.size)
        assertEquals("cur", r.entry.versions.first().blob) // snapshot kept
        assertEquals(listOf("b2"), r.freedBlobs)           // remaining v2 evicted+freed
        assertEquals("b1", r.entry.blob)                   // now current
    }

    @Test fun restoreVersion_unknown_id_is_noop() {
        val r = FileOps.restoreVersion(entryWithVersions(), "nope", nowIso = "t")
        assertEquals(entryWithVersions(), r.entry)
        assertTrue(r.freedBlobs.isEmpty())
    }
}
