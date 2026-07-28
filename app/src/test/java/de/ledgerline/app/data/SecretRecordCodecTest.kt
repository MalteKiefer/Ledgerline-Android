package de.ledgerline.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the sharded `/passwords/store` record codec: a decode→edit→encode round-trip must NOT
 * drop any field the Android UI doesn't model — foreign top-level keys AND the opaque
 * type-specific `fields` survive (data-integrity, §15). Mirrors [FileRecordCodec] /
 * [WorkspaceRecordCodec] test style.
 */
class SecretRecordCodecTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun unknown_top_level_field_survives_an_edit() {
        val raw = obj("""{"id":"s1","type":"login","title":"GitHub","zzFuture":123,"nested":{"k":1}}""")
        val item = SecretRecordCodec.decodeSecret(raw)
        val out = SecretRecordCodec.encodeSecret(item.copy(title = "GitLab"), raw)
        assertEquals("GitLab", out.getValue("title").jsonPrimitive.content)
        assertEquals("123", out.getValue("zzFuture").jsonPrimitive.content) // unknown scalar survives
        assertTrue(out["nested"] is JsonObject)                              // unknown object survives
    }

    @Test fun opaque_fields_round_trip_losslessly() {
        // `fields` carries type-specific + unknown keys the model treats as opaque.
        val raw = obj(
            """{"id":"s2","type":"card","title":"Visa",
               "fields":{"number":"4111","cvv":"123","futureField":{"deep":true}}}""",
        )
        val item = SecretRecordCodec.decodeSecret(raw)
        // The typed model exposes `fields` opaque; an edit elsewhere must not disturb it.
        val out = SecretRecordCodec.encodeSecret(item.copy(favorite = true), raw)
        val fields = out.getValue("fields").jsonObject
        assertEquals("4111", fields.getValue("number").jsonPrimitive.content)
        assertEquals("123", fields.getValue("cvv").jsonPrimitive.content)
        assertTrue(fields["futureField"] is JsonObject) // deep unknown structure preserved
        assertTrue(out.getValue("favorite").jsonPrimitive.content.toBoolean())
    }

    @Test fun trashed_iso_is_preserved_and_absence_stays_absent() {
        val active = SecretRecordCodec.decodeSecret(obj("""{"id":"s3","type":"login","title":"A"}"""))
        val outActive = SecretRecordCodec.encodeSecret(active, obj("""{"id":"s3","type":"login","title":"A"}"""))
        assertNull(outActive["trashed"]) // never trashed → no phantom key

        val trashedRaw = obj("""{"id":"s3","type":"login","title":"A","trashed":"2026-01-01T00:00:00Z"}""")
        val trashed = SecretRecordCodec.decodeSecret(trashedRaw)
        assertTrue(trashed.isTrashed)
        val out = SecretRecordCodec.encodeSecret(trashed, trashedRaw)
        assertEquals("2026-01-01T00:00:00Z", out.getValue("trashed").jsonPrimitive.content)
    }

    @Test fun untouched_record_gains_no_phantom_keys() {
        val raw = obj("""{"id":"s4","type":"login","title":"Min"}""")
        val item = SecretRecordCodec.decodeSecret(raw)
        val out = SecretRecordCodec.encodeSecret(item, raw)
        assertNull(out["favorite"]) // default false not present in raw → not emitted
        assertNull(out["tags"])
        assertNull(out["fields"])   // no fields key in raw → none injected
        assertNull(out["custom"])
        assertEquals("Min", out.getValue("title").jsonPrimitive.content)
    }

    @Test fun folder_unknown_field_and_role_survive() {
        val raw = obj("""{"id":"f1","name":"Work","role":"edit","future":"keep"}""")
        val folder = SecretRecordCodec.decodeFolder(raw)
        assertEquals("edit", folder.role)
        val out = SecretRecordCodec.encodeFolder(folder.copy(name = "Work2"), raw)
        assertEquals("Work2", out.getValue("name").jsonPrimitive.content)
        assertEquals("edit", out.getValue("role").jsonPrimitive.content)  // role preserved
        assertEquals("keep", out.getValue("future").jsonPrimitive.content) // unknown survives
    }

    @Test fun fresh_records_are_web_readable() {
        // No raw → built from the typed model in the web shape (new shard blob).
        val fresh = SecretRecordCodec.encodeSecret(
            de.ledgerline.app.domain.model.SecretItem(id = "n1", type = "login", title = "New"),
            raw = null,
        )
        assertEquals("n1", fresh.getValue("id").jsonPrimitive.content)
        assertEquals("login", fresh.getValue("type").jsonPrimitive.content)
        assertNull(fresh["favorite"]) // encodeDefaults=false drops false
        val freshFolder = SecretRecordCodec.encodeFolder(
            de.ledgerline.app.domain.model.SecretFolder(id = "g1", name = "F"),
            raw = null,
        )
        assertEquals("g1", freshFolder.getValue("id").jsonPrimitive.content)
    }
}
