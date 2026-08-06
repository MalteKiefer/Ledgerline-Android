package de.ledgerline.app.domain.share

import de.ledgerline.app.domain.model.NamedFolder
import org.junit.Assert.assertEquals
import org.junit.Test

/** Byte-shape parity for the sealed share manifests (web `_buildShareManifest`). */
class ShareManifestsTest {

    @Test fun file_manifest_matches_web_shape() {
        val json = ShareManifests.fileManifest(
            kind = "file", name = "a.txt",
            files = listOf(
                ShareManifests.FileEntryIn(
                    name = "a.txt", mime = "text/plain", size = 10, path = "",
                    ref = "blob1", key = """{"c":"x","n":"y"}""",
                ),
            ),
        )
        assertEquals(
            """{"kind":"file","name":"a.txt","files":[{"name":"a.txt","mime":"text/plain","size":10,"path":"","ref":"blob1","key":"{\"c\":\"x\",\"n\":\"y\"}"}]}""",
            json,
        )
    }

    @Test fun file_entry_defaults_empty_mime_to_octet_stream() {
        val json = ShareManifests.fileManifest(
            "file", "x",
            listOf(ShareManifests.FileEntryIn("x", "", 0, "", "b", """{"c":"c","n":"n"}""")),
        )
        assertEquals(true, json.contains(""""mime":"application/octet-stream""""))
    }

    @Test fun subtree_collects_descendants_inclusive() {
        val folders = listOf(
            NamedFolder(id = "root"),
            NamedFolder(id = "a", parent = "root"),
            NamedFolder(id = "b", parent = "a"),
            NamedFolder(id = "other"),
        )
        assertEquals(setOf("root", "a", "b"), ShareManifests.subtree("root", folders))
    }

    @Test fun relPath_joins_names_up_to_but_excluding_root() {
        val folders = listOf(
            NamedFolder(id = "root", name = "Root"),
            NamedFolder(id = "a", name = "A", parent = "root"),
            NamedFolder(id = "b", name = "B", parent = "a"),
        )
        val byId = folders.associateBy { it.id }
        assertEquals("A/B", ShareManifests.relPath("b", "root", byId))
        assertEquals("A", ShareManifests.relPath("a", "root", byId))
        assertEquals("", ShareManifests.relPath("root", "root", byId))
    }
}
