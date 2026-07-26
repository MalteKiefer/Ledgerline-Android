package de.ledgerline.app.data

import de.ledgerline.app.domain.model.Bookmark
import de.ledgerline.app.domain.model.Contact
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRecordCodecTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun unknown_top_level_field_survives_an_edit() {
        val raw = obj("""{"id":"a1","title":"Hi","content":"x","zzFuture":123,"nested":{"k":1}}""")
        val note = WorkspaceRecordCodec.decodeNote(raw)
        val edited = note.copy(title = "Changed")
        val out = WorkspaceRecordCodec.encodeNote(edited)
        assertEquals("Changed", out.getValue("title").jsonPrimitive.content)
        assertEquals("123", out.getValue("zzFuture").jsonPrimitive.content)  // unknown scalar survives
        assertTrue(out["nested"] is JsonObject)                               // unknown object survives
    }

    @Test fun trashed_renders_iso_or_false_not_bool() {
        val raw = obj("""{"id":"a1","title":"Hi","trashed":false}""")
        val note = WorkspaceRecordCodec.decodeNote(raw)
        assertFalse(note.trashed)
        // Trashing writes an ISO timestamp.
        val trashed = WorkspaceRecordCodec.encodeNote(note.copy(trashed = true)) { "2026-07-26T00:00:00Z" }
        assertEquals("2026-07-26T00:00:00Z", trashed.getValue("trashed").jsonPrimitive.content)
        // An already-trashed record (ISO) left untouched keeps its original timestamp.
        val wasTrashed = WorkspaceRecordCodec.decodeNote(obj("""{"id":"a1","trashed":"2026-01-01T00:00:00Z"}"""))
        assertTrue(wasTrashed.trashed)
        val kept = WorkspaceRecordCodec.encodeNote(wasTrashed) { "SHOULD-NOT-BE-USED" }
        assertEquals("2026-01-01T00:00:00Z", kept.getValue("trashed").jsonPrimitive.content)
    }

    @Test fun bookmark_folder_parentId_survives() {
        val raw = obj("""{"id":"f1","name":"Docs","parentId":"root","future":"keep"}""")
        val folder = WorkspaceRecordCodec.decodeBookmarkFolder(raw)
        assertEquals("root", folder.parent)
        val out = WorkspaceRecordCodec.encodeBookmarkFolder(folder.copy(name = "Docs2"))
        assertEquals("Docs2", out.getValue("name").jsonPrimitive.content)
        assertEquals("root", out.getValue("parentId").jsonPrimitive.content) // parentId preserved (not flattened)
        assertNull(out["parent"])                                            // never emits the wrong key
        assertEquals("keep", out.getValue("future").jsonPrimitive.content)
    }

    @Test fun contact_vatId_and_unknown_vcard_field_survive() {
        val raw = obj("""{"id":"c1","fn":"Jane","vatId":"DE123","xCustom":"prop","favorite":false}""")
        val c = WorkspaceRecordCodec.decodeContact(raw)
        assertEquals("DE123", c.vatId)
        val out = WorkspaceRecordCodec.encodeContact(c.copy(fn = "Jane Doe"))
        assertEquals("Jane Doe", out.getValue("fn").jsonPrimitive.content)
        assertEquals("DE123", out.getValue("vatId").jsonPrimitive.content)   // vatId round-trips
        assertEquals("prop", out.getValue("xCustom").jsonPrimitive.content)  // unknown field survives
    }

    @Test fun untouched_record_round_trips_without_phantom_keys() {
        // A minimal web record with only its own keys stays minimal (no favorite:false injected).
        val raw = obj("""{"id":"b1","url":"https://x","title":"X"}""")
        val bm: Bookmark = WorkspaceRecordCodec.decodeBookmark(raw)
        val out = WorkspaceRecordCodec.encodeBookmark(bm)
        assertNull(out["favorite"]) // default not present in raw → not emitted
        assertNull(out["tags"])
        assertEquals("https://x", out.getValue("url").jsonPrimitive.content)
    }
}
