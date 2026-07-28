package de.ledgerline.app.data

import de.ledgerline.app.domain.model.CustomField
import de.ledgerline.app.domain.model.SecretFolder
import de.ledgerline.app.domain.model.SecretItem
import de.ledgerline.app.domain.model.SecretVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Encodes the sharded `/passwords/store` records (`secrets`) and its one `secretFolders`
 * collection **byte-compatibly with the web client** and WITHOUT data loss — the same
 * raw-overlay strategy as [FileRecordCodec] / [GalleryRecordCodec].
 *
 * A web/iOS secret record may carry keys Android's typed [SecretItem] does not model at the
 * top level, and its type-specific `fields` are already kept **opaque** ([SecretItem.fields] =
 * raw [JsonObject]). To stay byte-identical AND lose nothing, encode from the record's
 * **original raw JsonObject** (captured on load) and overlay only the fields Android actually
 * edits; every untouched key (known or unknown) re-emits verbatim.
 *
 * A freshly-created record (no raw) is built from the typed model in the web shape; it is a
 * new shard blob either way, so it need only be web-READABLE.
 */
object SecretRecordCodec {
    /** kotlinx JSON that drops defaults/nulls (≈ web dropping `undefined`), tolerant on read. */
    val recordJson = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    // ---- Secret ----------------------------------------------------------------

    fun decodeSecret(obj: JsonObject): SecretItem = recordJson.decodeFromJsonElement(SecretItem.serializer(), obj)

    /**
     * Overlay the edited fields over [raw] (or an empty base for a fresh record). `id`/`type`/
     * `title` are ALWAYS emitted so a new record stays web-readable even when a value equals its
     * kotlin default (e.g. a login's `type == "login"`); every other field is presence-aware, so
     * an untouched record gains no phantom default key and every unknown web/iOS key re-emits
     * verbatim.
     */
    fun encodeSecret(s: SecretItem, raw: JsonObject?): JsonObject {
        val out = raw?.toMutableMap() ?: linkedMapOf()
        fun had(k: String) = raw?.containsKey(k) == true
        out["id"] = JsonPrimitive(s.id)
        out["type"] = JsonPrimitive(s.type)
        out["title"] = JsonPrimitive(s.title)
        if (had("favorite") || s.favorite) out["favorite"] = JsonPrimitive(s.favorite)
        setOrRemove(out, "folder", s.folder)
        if (had("tags") || s.tags.isNotEmpty()) out["tags"] = JsonArray(s.tags.map { JsonPrimitive(it) })
        if (had("custom") || s.custom.isNotEmpty()) {
            out["custom"] = JsonArray(s.custom.map { recordJson.encodeToJsonElement(CustomField.serializer(), it) })
        }
        if (had("icon") || s.icon.isNotEmpty()) out["icon"] = JsonPrimitive(s.icon)
        // `fields` is opaque: when untouched, s.fields IS the decoded raw["fields"] (same content
        // + order); when edited, this re-emits the new opaque object. Emit only when non-empty or
        // present in raw, so a record that never had a `fields` key stays free of a phantom {}.
        if (had("fields") || s.fields.isNotEmpty()) out["fields"] = s.fields
        setOrRemove(out, "created", s.created)
        setOrRemove(out, "updated", s.updated)
        if (had("versions") || s.versions.isNotEmpty()) {
            out["versions"] = JsonArray(s.versions.map { recordJson.encodeToJsonElement(SecretVersion.serializer(), it) })
        }
        // trashed is already an ISO string or absent (never a bool) → simple presence overlay.
        setOrRemove(out, "trashed", s.trashed)
        return JsonObject(out)
    }

    // ---- Folder ----------------------------------------------------------------

    fun decodeFolder(obj: JsonObject): SecretFolder = recordJson.decodeFromJsonElement(SecretFolder.serializer(), obj)

    fun encodeFolder(f: SecretFolder, raw: JsonObject?): JsonObject {
        val out = raw?.toMutableMap() ?: linkedMapOf()
        // id/name/role are the folder's full known shape; role is always emitted (web vault
        // folders carry it). Any unknown web key on [raw] re-emits verbatim.
        out["id"] = JsonPrimitive(f.id)
        out["name"] = JsonPrimitive(f.name)
        out["role"] = JsonPrimitive(f.role)
        return JsonObject(out)
    }

    // ---- helpers ---------------------------------------------------------------

    private fun setOrRemove(out: MutableMap<String, JsonElement>, key: String, value: String?) {
        if (value != null) out[key] = JsonPrimitive(value) else out.remove(key)
    }
}
