package de.ledgerline.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parses_manifest_with_unknown_and_missing_fields() {
        val text = """
          {"v":1,"gadgets":[1,2],
           "notes":[{"id":"n1","title":"Hi","content":"# H","pinned":true,"color":"red"}],
           "files":[{"id":"f1","blob":"b1","encFileKey":"{}","name":"a.txt","size":12}],
           "fileFolders":[{"id":"d1","name":"Docs"}]}
        """.trimIndent()
        val m = json.decodeFromString<WorkspaceManifest>(text)
        assertEquals(1, m.notes.size)
        assertTrue(m.notes[0].pinned)
        assertEquals("Docs", m.fileFolders[0].name)
        assertEquals(12L, m.files[0].size)
        assertEquals(0, m.bookmarks.size)
    }
}
