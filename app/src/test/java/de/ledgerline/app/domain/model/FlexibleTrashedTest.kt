package de.ledgerline.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The web writes `trashed` as `false`/`null` when live and as an ISO timestamp string
 * when trashed. A strict Boolean field used to throw on the string form and take the
 * whole manifest down. Verify the lenient serializer reads all three forms.
 */
class FlexibleTrashedTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun trashed_as_timestamp_string_reads_true() {
        val f = json.decodeFromString<FileEntry>("""{"id":"x","trashed":"2026-07-11T04:38:35.780Z"}""")
        assertTrue(f.trashed)
    }

    @Test fun trashed_false_and_null_and_absent_read_false() {
        assertFalse(json.decodeFromString<FileEntry>("""{"id":"x","trashed":false}""").trashed)
        assertFalse(json.decodeFromString<FileEntry>("""{"id":"x","trashed":null}""").trashed)
        assertFalse(json.decodeFromString<FileEntry>("""{"id":"x"}""").trashed)
    }

    @Test fun trashed_true_boolean_reads_true() {
        assertTrue(json.decodeFromString<FileEntry>("""{"id":"x","trashed":true}""").trashed)
    }

    @Test fun workspace_entities_read_timestamp_trashed() {
        assertTrue(json.decodeFromString<Note>("""{"id":"n","trashed":"2026-07-11T00:00:00Z"}""").trashed)
        assertTrue(json.decodeFromString<Bookmark>("""{"id":"b","trashed":"2026-07-11T00:00:00Z"}""").trashed)
        assertTrue(json.decodeFromString<TodoItem>("""{"id":"t","trashed":"2026-07-11T00:00:00Z"}""").trashed)
        assertTrue(json.decodeFromString<FileEntry>("""{"id":"f","trashed":"2026-07-11T00:00:00Z"}""").trashed)
    }

    @Test fun a_manifest_with_a_trashed_timestamp_entry_still_decodes_all() {
        val m = json.decodeFromString<WorkspaceManifest>(
            """{"files":[{"id":"a","trashed":false},{"id":"b","trashed":"2026-07-11T04:38:35.780Z"},{"id":"c"}]}"""
        )
        assertEquals(3, m.files.size)
        assertEquals(listOf(false, true, false), m.files.map { it.trashed })
    }
}
