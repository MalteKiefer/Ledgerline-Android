package de.ledgerline.app.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** No-data-loss round-trip for the health manifest: unknown web/iOS keys must survive. */
class HealthRecordCodecTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val webManifest = """
        {
          "v": 3,
          "syncCursor": "abc",
          "healthEntries": [
            { "id": "e1", "ts": "2026-07-25T08:00:00Z", "metric": "weight", "v": 79.5, "v2": null, "note": "morning", "src": "web" },
            { "id": "e2", "ts": "2026-07-25T09:00:00Z", "metric": "bp", "v": 120, "v2": 80, "note": "" }
          ],
          "healthProfile": { "birthdate": "1990-01-01", "heightCm": 180, "sex": "m", "weightGoalKg": 75, "units": { "weight": "kg", "glucose": "mgdl", "temp": "c" }, "avatar": "x" },
          "healthFasts": [
            { "id": "f1", "start": "2026-07-25T20:00:00Z", "end": null, "targetHours": 16, "note": "", "mood": "good" }
          ]
        }
    """.trimIndent()

    @Test fun decode_then_encode_preserves_unknown_keys() {
        val root = json.parseToJsonElement(webManifest) as JsonObject
        val m = HealthRecordCodec.decodeManifest(root)

        // Known fields decoded correctly.
        assertEquals(2, m.entries.size)
        assertEquals(79.5, m.entries[0].v, 1e-9)
        assertEquals("weight", m.entries[0].metric)
        assertEquals(80.0, m.entries[1].v2!!, 1e-9)
        assertEquals("1990-01-01", m.profile.birthdate)
        assertEquals(180.0, m.profile.heightCm!!, 1e-9)
        assertEquals(16, m.fasts[0].targetHours)
        assertNull(m.fasts[0].end)

        val out = HealthRecordCodec.encodeManifest(m)

        // Unknown top-level key survives.
        assertEquals("abc", out["syncCursor"]!!.jsonPrimitive.content)
        assertEquals(3, out["v"]!!.jsonPrimitive.int)
        // Unknown per-entry key survives.
        val e1 = (out["healthEntries"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("web", e1["src"]!!.jsonPrimitive.content)
        // Integer-valued bp stays an integer token (no "120.0").
        val e2 = (out["healthEntries"] as kotlinx.serialization.json.JsonArray)[1].jsonObject
        assertEquals("120", e2["v"].toString())
        assertEquals("80", e2["v2"].toString())
        // Unknown profile + fast keys survive.
        assertEquals("x", out["healthProfile"]!!.jsonObject["avatar"]!!.jsonPrimitive.content)
        val f1 = (out["healthFasts"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("good", f1["mood"]!!.jsonPrimitive.content)
    }

    @Test fun new_entry_encodes_web_readable_shape() {
        val m = HealthManifest(entries = listOf(HealthEntry(id = "n1", ts = "2026-07-27T10:00:00Z", metric = "temp", v = 36.6)))
        val out = HealthRecordCodec.encodeManifest(m)
        val e = (out["healthEntries"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("36.6", e["v"].toString())
        assertEquals("null", e["v2"].toString()) // non-bp → explicit null, web parity
        assertEquals("temp", e["metric"]!!.jsonPrimitive.content)
    }
}
