package de.ledgerline.app.data

import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.NamedFolder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Encodes `/files/store` records **byte-compatibly with the web client** and WITHOUT data
 * loss — the same raw-overlay strategy as [GalleryRecordCodec].
 *
 * The web/iOS file record carries fields Android's typed [FileEntry] does not model
 * (`encMeta`, `updated`, `openedAt`, `note`, `textRef`, `embRef`, `share`, `activity`,
 * `sharedVaultId`, …) and renders `trashed` as an ISO string or null. To stay byte-identical
 * AND lose nothing, encode from the record's **original raw JsonObject** (captured on load)
 * and overlay only the fields Android actually edits; untouched keys re-emit verbatim.
 *
 * A freshly-imported record (no raw) is built from the typed model in the web shape; it is a
 * new shard blob either way, so it need only be web-READABLE.
 */
object FileRecordCodec {
    /** kotlinx JSON that drops defaults/nulls (≈ web dropping `undefined`). */
    val recordJson = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }

    // ---- File ------------------------------------------------------------------

    fun decodeFile(obj: JsonObject): FileEntry = recordJson.decodeFromJsonElement(FileEntry.serializer(), obj)

    fun encodeFile(f: FileEntry, raw: JsonObject?, nowIso: () -> String = ::isoNow): JsonObject {
        if (raw == null) return freshFile(f, nowIso)
        val out = raw.toMutableMap()
        // Overlay only the fields Android edits; preserve every unknown web key.
        out["name"] = JsonPrimitive(f.name)
        if (raw.containsKey("favorite") || f.favorite) out["favorite"] = JsonPrimitive(f.favorite)
        setOrRemove(out, "folder", f.folder)
        if (raw.containsKey("tags") || f.tags.isNotEmpty()) out["tags"] = JsonArray(f.tags.map { JsonPrimitive(it) })
        if (raw.containsKey("versions") || f.versions.isNotEmpty()) {
            out["versions"] = JsonArray(f.versions.map { recordJson.encodeToJsonElement(de.ledgerline.app.domain.model.FileVersion.serializer(), it) })
        }
        applyTrashed(out, f.trashed, raw["trashed"], nowIso)
        return JsonObject(out)
    }

    private fun freshFile(f: FileEntry, nowIso: () -> String): JsonObject {
        val out = recordJson.encodeToJsonElement(FileEntry.serializer(), f).jsonObject.toMutableMap()
        // Web renders trashed as ISO-or-null, not a boolean (FlexibleTrashedSerializer would emit bool).
        out.remove("trashed"); if (f.trashed) out["trashed"] = JsonPrimitive(nowIso())
        return JsonObject(out)
    }

    // ---- Folder ----------------------------------------------------------------

    fun decodeFolder(obj: JsonObject): NamedFolder = recordJson.decodeFromJsonElement(NamedFolder.serializer(), obj)

    fun encodeFolder(folder: NamedFolder, raw: JsonObject?): JsonObject {
        if (raw == null) return recordJson.encodeToJsonElement(NamedFolder.serializer(), folder).jsonObject
        val out = raw.toMutableMap()
        out["name"] = JsonPrimitive(folder.name)
        setOrRemove(out, "parent", folder.parent)
        // color/icon are Android-only extras; only emit when set (web folders lack them).
        if (raw.containsKey("color") || folder.color.isNotEmpty()) out["color"] = JsonPrimitive(folder.color)
        if (raw.containsKey("icon") || folder.icon.isNotEmpty()) out["icon"] = JsonPrimitive(folder.icon)
        return JsonObject(out)
    }

    // ---- helpers ---------------------------------------------------------------

    private fun applyTrashed(out: MutableMap<String, JsonElement>, trashed: Boolean, rawTrashed: JsonElement?, nowIso: () -> String) {
        val wasTrashed = truthy(rawTrashed)
        if (trashed == wasTrashed) return // unchanged → keep the raw token (null / ISO)
        if (trashed) out["trashed"] = JsonPrimitive(nowIso()) else out["trashed"] = JsonNull
    }

    private fun setOrRemove(out: MutableMap<String, JsonElement>, key: String, value: String?) {
        if (value != null) out[key] = JsonPrimitive(value) else out.remove(key)
    }

    private fun truthy(el: JsonElement?): Boolean =
        el != null && el !is JsonNull && !(el is JsonPrimitive && (el.content == "false" || el.content.isEmpty()))

    private fun isoNow(): String = java.time.Instant.now().toString()
}
