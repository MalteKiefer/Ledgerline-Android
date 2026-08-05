package de.ledgerline.app.core.offline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ports the web `__tests__/manifest-merge.test.js` vectors 1:1 (spec §4). */
class ManifestMergeTest {
    private fun j(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun merge(b: String, o: String, s: String): JsonObject = ManifestMerge.mergeManifest(j(b), j(o), j(s))
    private fun ids(obj: JsonObject, key: String): List<String> =
        (obj[key] as JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content }.sorted()

    @Test fun concurrent_add_keeps_both() {
        val m = merge(
            """{"v":3,"notes":[{"id":"a","t":"A"}]}""",
            """{"v":3,"notes":[{"id":"a","t":"A"},{"id":"b","t":"B"}]}""",
            """{"v":3,"notes":[{"id":"a","t":"A"},{"id":"c","t":"C"}]}""",
        )
        assertEquals(listOf("a", "b", "c"), ids(m, "notes"))
    }

    @Test fun our_edit_applies_while_winner_added() {
        val m = merge(
            """{"tracks":[{"id":"t1"}],"settings":{"tol":100}}""",
            """{"tracks":[{"id":"t1"}],"settings":{"tol":250}}""",
            """{"tracks":[{"id":"t1"},{"id":"t2"}],"settings":{"tol":100}}""",
        )
        assertEquals(listOf("t1", "t2"), ids(m, "tracks"))
        assertEquals(250, m["settings"]!!.jsonObject["tol"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun delete_vs_add() {
        val m = merge(
            """{"notes":[{"id":"a"},{"id":"b"}]}""",
            """{"notes":[{"id":"a"}]}""",
            """{"notes":[{"id":"a"},{"id":"b"},{"id":"c"}]}""",
        )
        assertEquals(listOf("a", "c"), ids(m, "notes"))
    }

    @Test fun modify_own_record_keeps_winner_add() {
        val m = merge(
            """{"notes":[{"id":"a","t":"A"}]}""",
            """{"notes":[{"id":"a","t":"A2"}]}""",
            """{"notes":[{"id":"a","t":"A"},{"id":"z","t":"Z"}]}""",
        )
        assertEquals(listOf("a", "z"), ids(m, "notes"))
        val a = (m["notes"] as JsonArray).map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "a" }
        assertEquals("A2", a["t"]!!.jsonPrimitive.content)
    }

    @Test fun id_map_merge_key_by_key() {
        val m = merge("""{"couplings":{}}""", """{"couplings":{"p1":{"track":"t1"}}}""", """{"couplings":{"p2":{"track":"t2"}}}""")
        assertEquals(listOf("p1", "p2"), m["couplings"]!!.jsonObject.keys.sorted())
    }

    @Test fun preserves_foreign_toplevel_key() {
        val m = merge("""{"notes":[]}""", """{"notes":[{"id":"a"}]}""", """{"notes":[],"extra":{"keep":true}}""")
        assertEquals(true, m["extra"]!!.jsonObject["keep"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun scalar_ours_if_changed_else_winner() {
        assertEquals(6, merge("""{"seq":5}""", """{"seq":6}""", """{"seq":7}""")["seq"]!!.jsonPrimitive.content.toInt())
        assertEquals(7, merge("""{"seq":5}""", """{"seq":5}""", """{"seq":7}""")["seq"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun object_key_delete_and_winner_add() {
        val m = ManifestMerge.mergeObjectByKey(j("""{"a":1,"b":2}"""), j("""{"a":1}"""), j("""{"a":1,"b":2,"c":3}"""))
        assertEquals(setOf("a", "c"), m.keys)
    }

    @Test fun scalar_array_set_union() {
        val m = ManifestMerge.mergeArrayById(
            JsonArray(listOf(JsonPrimitive("a"))),
            JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
            JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("c"))),
        )
        assertEquals(listOf("a", "b", "c"), m.map { it.jsonPrimitive.content }.sorted())
    }

    @Test fun nested_id_array_deep_merge_same_record() {
        val m = merge(
            """{"invoices":[{"id":"X","versions":[{"id":"v1"}]}]}""",
            """{"invoices":[{"id":"X","versions":[{"id":"v1"},{"id":"v2B"}]}]}""",
            """{"invoices":[{"id":"X","versions":[{"id":"v1"},{"id":"v2A"}]}]}""",
        )
        val versions = (m["invoices"] as JsonArray)[0].jsonObject["versions"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }.sorted()
        assertEquals(listOf("v1", "v2A", "v2B"), versions)
    }

    @Test fun divergent_scalar_fields_per_key_on_shared_record() {
        val m = merge(
            """{"c":[{"id":"1","a":1,"b":1}]}""",
            """{"c":[{"id":"1","a":2,"b":1}]}""",
            """{"c":[{"id":"1","a":1,"b":9}]}""",
        )
        val rec = (m["c"] as JsonArray)[0].jsonObject
        assertEquals(2, rec["a"]!!.jsonPrimitive.content.toInt())
        assertEquals(9, rec["b"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun nested_object_key_id_array_merge() {
        val m = merge(
            """{"s":[{"id":"X","fields":{"passkeys":[{"id":"a"}]}}]}""",
            """{"s":[{"id":"X","fields":{"passkeys":[{"id":"a"},{"id":"ours"}]}}]}""",
            """{"s":[{"id":"X","fields":{"passkeys":[{"id":"a"},{"id":"srv"}]}}]}""",
        )
        val pk = (m["s"] as JsonArray)[0].jsonObject["fields"]!!.jsonObject["passkeys"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }.sorted()
        assertEquals(listOf("a", "ours", "srv"), pk)
    }

    @Test fun nested_scalar_set_union_in_field() {
        val m = merge(
            """{"s":[{"id":"X","fields":{"urls":["a"]}}]}""",
            """{"s":[{"id":"X","fields":{"urls":["a","b"]}}]}""",
            """{"s":[{"id":"X","fields":{"urls":["a","c"]}}]}""",
        )
        val urls = (m["s"] as JsonArray)[0].jsonObject["fields"]!!.jsonObject["urls"]!!.jsonArray
            .map { it.jsonPrimitive.content }.sorted()
        assertEquals(listOf("a", "b", "c"), urls)
    }
}
