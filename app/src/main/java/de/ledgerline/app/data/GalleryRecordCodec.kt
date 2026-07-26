package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.dec6
import de.ledgerline.app.domain.model.GalleryAlbum
import de.ledgerline.app.domain.model.GalleryPerson
import de.ledgerline.app.domain.model.GalleryPhoto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Encodes gallery store records **byte-compatibly with the web client** and — this is
 * the key property — WITHOUT data loss.
 *
 * The web hot record carries fields Android's typed model does not know (`archived`,
 * `caption`, `embModel`, `geoChecked`, pipeline flags, person `centroid` FLOAT arrays,
 * extra contact-link fields, …) and uses web-specific renderings (`lat`/`lng` as
 * `dec6` STRINGS, `trashed` as an ISO string or null). To stay byte-identical AND lose
 * nothing, encode from the record's **original raw JsonObject** (captured on load) and
 * overlay only the fields Android actually edits. Untouched keys — including raw float
 * tokens like `1e-7` in `centroid` — are re-emitted verbatim (kotlinx preserves the
 * number literal), so no JS number formatter is needed.
 *
 * A freshly-imported record (no raw) is built from the typed model in the web shape
 * (lat/lng dec6, trashed ISO-or-omitted, duration int); it is a new shard either way,
 * so it need only be web-READABLE, not byte-identical to a web import.
 */
object GalleryRecordCodec {
    /** kotlinx JSON that drops defaults/nulls (≈ web dropping `undefined`). */
    val recordJson = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }

    // ---- Photo ----------------------------------------------------------------

    fun encodePhoto(p: GalleryPhoto, raw: JsonObject?, nowIso: () -> String = ::isoNow): JsonObject {
        if (raw == null) return freshPhoto(p, nowIso)
        val out = raw.toMutableMap()
        // Overlay only the fields Android edits, rendered the web way. Presence-aware:
        // put a field only when raw already had it (preserve web's key set) OR Android set
        // a non-default value (a genuine new edit) — so an untouched record round-trips
        // byte-identically (no phantom rotation:0 / flipH:false keys).
        if (raw.containsKey("favorite") || p.favorite) out["favorite"] = JsonPrimitive(p.favorite)
        if (raw.containsKey("rotation") || p.rotation != 0) out["rotation"] = JsonPrimitive(p.rotation)
        if (raw.containsKey("flipH") || p.flipH) out["flipH"] = JsonPrimitive(p.flipH)
        if (raw.containsKey("flipV") || p.flipV) out["flipV"] = JsonPrimitive(p.flipV)
        p.name?.let { out["name"] = JsonPrimitive(it) }
        p.taken_at?.let { out["taken_at"] = JsonPrimitive(it) }
        p.lat?.let { out["lat"] = JsonPrimitive(dec6(it)) }
        p.lng?.let { out["lng"] = JsonPrimitive(dec6(it)) }
        if (raw.containsKey("faceCropRefs") || p.faceCropRefs.isNotEmpty()) {
            out["faceCropRefs"] = JsonArray(p.faceCropRefs.map { JsonPrimitive(it) })
        }
        applyTrashed(out, p.trashed, raw["trashed"], nowIso)
        return JsonObject(out)
    }

    private fun freshPhoto(p: GalleryPhoto, nowIso: () -> String): JsonObject {
        val out = recordJson.encodeToJsonElement(GalleryPhoto.serializer(), p).jsonObject.toMutableMap()
        // Web renderings the typed serializer can't produce.
        out.remove("lat"); p.lat?.let { out["lat"] = JsonPrimitive(dec6(it)) }
        out.remove("lng"); p.lng?.let { out["lng"] = JsonPrimitive(dec6(it)) }
        out.remove("duration"); p.duration?.let { out["duration"] = JsonPrimitive(it.toLong()) } // web: int
        out.remove("trashed"); if (p.trashed) out["trashed"] = JsonPrimitive(nowIso())
        return JsonObject(out)
    }

    // ---- Album ----------------------------------------------------------------

    fun encodeAlbum(a: GalleryAlbum, raw: JsonObject?): JsonObject {
        if (raw == null) return recordJson.encodeToJsonElement(GalleryAlbum.serializer(), a).jsonObject
        val out = raw.toMutableMap()
        out["name"] = JsonPrimitive(a.name)
        out["photoIds"] = JsonArray(a.photoIds.map { JsonPrimitive(it) })
        a.cover?.let { out["cover"] = JsonPrimitive(it) }
        FileRecordCodec.applyShare(out, a.share, raw["share"])
        return JsonObject(out)
    }

    // ---- Person ---------------------------------------------------------------
    // centroid and any other float/unknown fields stay in raw → byte-exact + no loss.

    fun encodePerson(p: GalleryPerson, raw: JsonObject?): JsonObject {
        if (raw == null) return freshPerson(p)
        val out = raw.toMutableMap()
        out["name"] = JsonPrimitive(p.name)
        out["hidden"] = JsonPrimitive(p.hidden)
        // Faces: keep the raw tokens byte-exact when unchanged; only re-emit (web-shaped)
        // when Android actually edited the face list (e.g. a person merge).
        val decodedRawFaces = (raw["faces"] as? JsonArray)?.map {
            recordJson.decodeFromJsonElement(de.ledgerline.app.domain.model.PersonFace.serializer(), it)
        } ?: emptyList()
        if (p.faces != decodedRawFaces) out["faces"] = JsonArray(p.faces.map(::faceJson))
        setOrRemove(out, "contactId", p.contactId)
        setOrRemove(out, "contactName", p.contactName)
        return JsonObject(out)
    }

    private fun freshPerson(p: GalleryPerson): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(p.id))
        put("name", JsonPrimitive(p.name))
        put("hidden", JsonPrimitive(p.hidden))
        if (p.faces.isNotEmpty()) put("faces", JsonArray(p.faces.map(::faceJson)))
        p.contactId?.let { put("contactId", JsonPrimitive(it)) }
        p.contactName?.let { put("contactName", JsonPrimitive(it)) }
    }

    /** Web-shaped face: photoId + idx always present, cropRef/Key when set, manual only when true. */
    private fun faceJson(f: de.ledgerline.app.domain.model.PersonFace): JsonObject = buildJsonObject {
        put("photoId", JsonPrimitive(f.photoId))
        put("idx", JsonPrimitive(f.idx))
        f.cropRef?.let { put("cropRef", JsonPrimitive(it)) }
        f.cropKey?.let { put("cropKey", JsonPrimitive(it)) }
        if (f.manual) put("manual", JsonPrimitive(true))
    }

    // ---- Decode: typed view + the raw record for round-trip ---------------------

    fun decodePhoto(obj: JsonObject): GalleryPhoto = recordJson.decodeFromJsonElement(GalleryPhoto.serializer(), obj)
    fun decodeAlbum(obj: JsonObject): GalleryAlbum = recordJson.decodeFromJsonElement(GalleryAlbum.serializer(), obj)
    fun decodePerson(obj: JsonObject): GalleryPerson = recordJson.decodeFromJsonElement(GalleryPerson.serializer(), obj)

    // ---- helpers --------------------------------------------------------------

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
