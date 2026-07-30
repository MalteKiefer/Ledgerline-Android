package de.ledgerline.app.core.offline

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Splits a sealed-manifest **root** JsonObject (as produced by a repo's `encodeManifest`) into
 * id-keyed collections for [StoreDelta] diffing, and applies a delta back onto a root. Lets any
 * monolith store (health, explore, workspace modules) participate in the offline write outbox
 * without bespoke delta code: it just declares which top-level keys are id-lists vs singletons.
 *
 * - **listKeys**: top-level arrays of records each carrying an `id` → collection keyed by that id.
 * - **singletonKeys**: a single top-level object (e.g. a profile/settings blob) → a one-entry
 *   collection under the fixed id [SINGLETON] (whole-object last-write-wins).
 *
 * Foreign top-level keys not listed are left untouched by [apply], so unknown web/iOS fields and
 * un-edited collections on the server are always preserved (§11/§15).
 */
object ManifestDelta {
    const val SINGLETON = "_"

    fun collections(
        root: JsonObject,
        listKeys: List<String>,
        singletonKeys: List<String> = emptyList(),
    ): Map<String, Map<String, JsonObject>> {
        val out = LinkedHashMap<String, Map<String, JsonObject>>()
        for (k in listKeys) {
            val arr = root[k] as? JsonArray ?: continue
            val byId = LinkedHashMap<String, JsonObject>()
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                val id = (o["id"] as? JsonPrimitive)?.content ?: continue
                byId[id] = o
            }
            out[k] = byId
        }
        for (k in singletonKeys) {
            val o = root[k] as? JsonObject ?: continue
            out[k] = mapOf(SINGLETON to o)
        }
        return out
    }

    fun apply(
        root: JsonObject,
        delta: StoreDelta,
        listKeys: List<String>,
        singletonKeys: List<String> = emptyList(),
    ): JsonObject {
        val map = root.toMutableMap()
        for (k in listKeys) {
            val cd = delta.collections[k] ?: continue
            if (cd.isEmpty) continue
            val byId = LinkedHashMap<String, JsonObject>()
            (root[k] as? JsonArray).orEmpty().forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val id = (o["id"] as? JsonPrimitive)?.content ?: return@forEach
                byId[id] = o
            }
            cd.deletes.forEach { byId.remove(it) }
            cd.upserts.forEach { (id, obj) -> byId[id] = obj }
            map[k] = JsonArray(byId.values.toList())
        }
        for (k in singletonKeys) {
            val cd = delta.collections[k] ?: continue
            cd.upserts[SINGLETON]?.let { map[k] = it }
        }
        return JsonObject(map)
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
