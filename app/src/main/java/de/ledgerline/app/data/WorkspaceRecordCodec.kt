package de.ledgerline.app.data

import de.ledgerline.app.domain.model.Bookmark
import de.ledgerline.app.domain.model.Contact
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.model.TodoItem
import de.ledgerline.app.domain.model.TodoList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/**
 * Raw-JSON overlay codec for the workspace record types (notes/todos/bookmarks/contacts), the same
 * no-data-loss strategy as [FileRecordCodec]/[GalleryRecordCodec]: decode each record into its
 * typed model **and** keep its original `JsonObject`; on encode, overlay only the owned fields
 * (presence-aware, web-shaped) so every Web/iOS field the Kotlin model doesn't know survives the
 * round-trip. `trashed` is rendered `false | ISO-8601` (never collapsed to a bare bool); bookmark
 * folders map `parent ↔ parentId`.
 */
object WorkspaceRecordCodec {
    // Decode: tolerant of web-authored variance. Encode: all owned keys (incl. defaults) so the
    // presence-aware diff below can decide per-key what to emit.
    private val dec = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val enc = Json { encodeDefaults = true; explicitNulls = false }

    // ---- Notes ----------------------------------------------------------------
    fun decodeNote(o: JsonObject): Note = dec.decodeFromJsonElement(Note.serializer(), o).copy(raw = o)
    fun encodeNote(n: Note, now: () -> String = ::isoNow): JsonObject =
        overlay(Note.serializer(), n.copy(raw = JsonObject(emptyMap())), Note(), n.raw, trashed = n.trashed, now = now)

    // ---- Bookmarks ------------------------------------------------------------
    fun decodeBookmark(o: JsonObject): Bookmark = dec.decodeFromJsonElement(Bookmark.serializer(), o).copy(raw = o)
    fun encodeBookmark(b: Bookmark, now: () -> String = ::isoNow): JsonObject =
        overlay(Bookmark.serializer(), b.copy(raw = JsonObject(emptyMap())), Bookmark(), b.raw, trashed = b.trashed, now = now)

    // ---- Bookmark folders (parent ↔ parentId) ---------------------------------
    fun decodeBookmarkFolder(o: JsonObject): NamedFolder {
        val parent = (o["parentId"] ?: o["parent"])?.let { (it as? JsonPrimitive)?.contentOrNull() }
        return NamedFolder(
            id = str(o, "id"), name = str(o, "name"), parent = parent,
            color = str(o, "color"), icon = str(o, "icon"), raw = o,
        )
    }

    fun encodeBookmarkFolder(f: NamedFolder): JsonObject {
        val out = f.raw.toMutableMap()
        out.remove("parent") // web bookmark folders use parentId only
        out["name"] = JsonPrimitive(f.name)
        setOrRemove(out, "parentId", f.parent)
        presence(out, f.raw, "color", f.color, f.color.isNotEmpty())
        presence(out, f.raw, "icon", f.icon, f.icon.isNotEmpty())
        if (!out.containsKey("id")) out["id"] = JsonPrimitive(f.id)
        return JsonObject(out)
    }

    // ---- Todos ----------------------------------------------------------------
    fun decodeTodo(o: JsonObject): TodoItem = dec.decodeFromJsonElement(TodoItem.serializer(), o).copy(raw = o)
    fun encodeTodo(t: TodoItem, now: () -> String = ::isoNow): JsonObject =
        overlay(TodoItem.serializer(), t.copy(raw = JsonObject(emptyMap())), TodoItem(), t.raw, trashed = t.trashed, now = now)

    fun decodeTodoList(o: JsonObject): TodoList = dec.decodeFromJsonElement(TodoList.serializer(), o).copy(raw = o)
    fun encodeTodoList(l: TodoList): JsonObject =
        overlay(TodoList.serializer(), l.copy(raw = JsonObject(emptyMap())), TodoList(), l.raw, trashed = null, now = ::isoNow)

    // ---- Contacts -------------------------------------------------------------
    fun decodeContact(o: JsonObject): Contact = dec.decodeFromJsonElement(Contact.serializer(), o).copy(raw = o)
    fun encodeContact(c: Contact, now: () -> String = ::isoNow): JsonObject =
        overlay(Contact.serializer(), c.copy(raw = JsonObject(emptyMap())), Contact(), c.raw, trashed = c.trashed, now = now)

    // ---- generic presence-aware overlay ---------------------------------------

    /**
     * Overlay [typed]'s owned fields onto [raw], emitting a key only when [raw] already had it OR
     * the value differs from [default] (so an untouched record round-trips byte-identically and
     * unknown keys survive). `trashed` (when the type has it) is rendered `false | ISO`.
     */
    private fun <T> overlay(
        ser: KSerializer<T>, typed: T, default: T, raw: JsonObject,
        trashed: Boolean?, now: () -> String,
    ): JsonObject {
        val typedFull = enc.encodeToJsonElement(ser, typed).jsonObject
        val defFull = enc.encodeToJsonElement(ser, default).jsonObject
        val out = raw.toMutableMap()
        for ((k, v) in typedFull) {
            if (k == "trashed" && trashed != null) continue // handled below
            val isDefault = v == defFull[k]
            if (raw.containsKey(k) || !isDefault) out[k] = v else out.remove(k)
        }
        if (trashed != null) applyTrashed(out, trashed, raw["trashed"], now)
        return JsonObject(out)
    }

    private fun applyTrashed(out: MutableMap<String, JsonElement>, trashed: Boolean, rawTrashed: JsonElement?, now: () -> String) {
        val wasTrashed = truthy(rawTrashed)
        if (trashed == wasTrashed) { // unchanged → keep the raw token (false / ISO)
            if (rawTrashed != null) out["trashed"] = rawTrashed
            return
        }
        out["trashed"] = if (trashed) JsonPrimitive(now()) else JsonPrimitive(false)
    }

    private fun presence(out: MutableMap<String, JsonElement>, raw: JsonObject, key: String, value: String, nonDefault: Boolean) {
        if (raw.containsKey(key) || nonDefault) out[key] = JsonPrimitive(value) else out.remove(key)
    }

    private fun setOrRemove(out: MutableMap<String, JsonElement>, key: String, value: String?) {
        if (value != null) out[key] = JsonPrimitive(value) else out.remove(key)
    }

    private fun str(o: JsonObject, key: String): String = (o[key] as? JsonPrimitive)?.contentOrNull() ?: ""
    private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content
    private fun truthy(el: JsonElement?): Boolean =
        el != null && el !is JsonNull && !(el is JsonPrimitive && (el.content == "false" || el.content.isEmpty()))
    private fun isoNow(): String = Instant.now().toString()
}
