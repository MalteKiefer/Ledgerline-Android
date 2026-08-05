package de.ledgerline.app.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decrypted `store/calendar` module (GET/PUT /store/calendar). Zero-knowledge — the server never
 * sees event times or content. Byte-shape mirrors the web `CalendarStoreModel`
 * (`{ v:3, calendars:[], events:[], settings:{} }`). Known fields are typed; the original decoded
 * JSON is kept in `raw` on each record so foreign/future keys (recurrence overrides, reminders,
 * nested location) survive an Android read-modify-write. First pass renders single + start-day
 * occurrences; full RRULE expansion is a follow-up.
 */
data class CalendarModel(
    val id: String,
    val name: String,
    val color: String = "#7066f5",
    val isDefault: Boolean = false,
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class EventLocation(val label: String = "", val lat: Double? = null, val lng: Double? = null)

data class CalendarEvent(
    val id: String,
    val calendarId: String,
    val title: String,
    val description: String = "",
    val allDay: Boolean = false,
    /** ISO datetime, or `yyyy-MM-dd` for all-day. */
    val start: String,
    /** ISO datetime, or `yyyy-MM-dd` (inclusive) for all-day; blank = none. */
    val end: String = "",
    val tz: String = "",
    val location: EventLocation? = null,
    /** RFC-5545 RRULE (no DTSTART); blank = single event. */
    val rrule: String = "",
    /** Excluded occurrence days (yyyy-MM-dd) of a recurring series. */
    val exdates: List<String> = emptyList(),
    /** This record overrides one occurrence (day) of [overrideOf]. */
    val recurrenceId: String = "",
    val overrideOf: String = "",
    val status: String = "confirmed",
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class CalendarManifest(
    val calendars: List<CalendarModel> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
    /** Client display feeds (birthdays/holidays/subscriptions) — preserved verbatim for now. */
    val settings: JsonObject = JsonObject(emptyMap()),
    /** Unknown top-level keys, preserved verbatim. */
    val extra: JsonObject = JsonObject(emptyMap()),
)

data class CalendarStore(val manifest: CalendarManifest, val version: Int)

/** (De)serialisation for the calendar manifest with a raw-JSON overlay (no field loss). */
object CalendarRecordCodec {
    private const val CALENDARS = "calendars"
    private const val EVENTS = "events"
    private const val SETTINGS = "settings"
    private val TOP_OWNED = setOf("v", CALENDARS, EVENTS, SETTINGS)

    fun decodeManifest(root: JsonObject): CalendarManifest {
        val calendars = (root[CALENDARS] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::decodeCalendar) }
        val events = (root[EVENTS] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::decodeEvent) }
        val settings = root[SETTINGS] as? JsonObject ?: JsonObject(emptyMap())
        val extra = JsonObject(root.filterKeys { it !in TOP_OWNED })
        return CalendarManifest(calendars, events, settings, extra)
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private fun str(o: JsonObject, k: String): String = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
    private fun bool(o: JsonObject, k: String): Boolean = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun decodeCalendar(o: JsonObject): CalendarModel? {
        val id = str(o, "id").ifBlank { return null }
        return CalendarModel(
            id = id,
            name = str(o, "name"),
            color = str(o, "color").ifBlank { "#7066f5" },
            isDefault = bool(o, "isDefault"),
            raw = o,
        )
    }

    private fun decodeEvent(o: JsonObject): CalendarEvent? {
        val id = str(o, "id").ifBlank { return null }
        val loc = (o["location"] as? JsonObject)?.let {
            EventLocation(
                label = str(it, "label"),
                lat = (it["lat"] as? JsonPrimitive)?.doubleOrNull,
                lng = (it["lng"] as? JsonPrimitive)?.doubleOrNull,
            )
        }
        return CalendarEvent(
            id = id,
            calendarId = str(o, "calendarId"),
            title = str(o, "title"),
            description = str(o, "description"),
            allDay = bool(o, "allDay"),
            start = str(o, "start"),
            end = str(o, "end"),
            tz = str(o, "tz"),
            location = loc,
            rrule = str(o, "rrule"),
            exdates = (o["exdates"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map { it.take(10) },
            recurrenceId = str(o, "recurrenceId"),
            overrideOf = str(o, "overrideOf"),
            status = str(o, "status").ifBlank { "confirmed" },
            raw = o,
        )
    }

    // ---- encode (raw overlay) ----------------------------------------------

    fun encodeManifest(m: CalendarManifest): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["v"] = JsonPrimitive(3)
        for ((k, v) in m.extra) if (k !in TOP_OWNED) out[k] = v
        out[CALENDARS] = JsonArray(m.calendars.map(::encodeCalendar))
        out[EVENTS] = JsonArray(m.events.map(::encodeEvent))
        out[SETTINGS] = m.settings
        return JsonObject(out)
    }

    private fun encodeCalendar(c: CalendarModel): JsonObject {
        val out = c.raw.toMutableMap()
        out["id"] = JsonPrimitive(c.id)
        out["name"] = JsonPrimitive(c.name)
        out["color"] = JsonPrimitive(c.color)
        out["isDefault"] = JsonPrimitive(c.isDefault)
        return JsonObject(out)
    }

    private fun encodeEvent(e: CalendarEvent): JsonObject {
        // Overlay only the scalar fields the app owns onto the original record — nested
        // location / exdates / reminders / recurrence overrides ride along verbatim in `raw`.
        val out = e.raw.toMutableMap()
        out["id"] = JsonPrimitive(e.id)
        out["calendarId"] = JsonPrimitive(e.calendarId)
        out["title"] = JsonPrimitive(e.title)
        out["description"] = JsonPrimitive(e.description)
        out["allDay"] = JsonPrimitive(e.allDay)
        out["start"] = JsonPrimitive(e.start)
        if (e.end.isNotBlank()) out["end"] = JsonPrimitive(e.end) else out.remove("end")
        if (e.tz.isNotBlank()) out["tz"] = JsonPrimitive(e.tz)
        out["status"] = JsonPrimitive(e.status)
        if (e.rrule.isNotBlank()) out["rrule"] = JsonPrimitive(e.rrule) else out["rrule"] = JsonNull
        if (e.exdates.isNotEmpty()) out["exdates"] = JsonArray(e.exdates.map { JsonPrimitive(it) }) else out.remove("exdates")
        if (e.recurrenceId.isNotBlank()) out["recurrenceId"] = JsonPrimitive(e.recurrenceId) else out.remove("recurrenceId")
        if (e.overrideOf.isNotBlank()) out["overrideOf"] = JsonPrimitive(e.overrideOf) else out.remove("overrideOf")
        if (e.location != null) {
            val locOut = (e.raw["location"] as? JsonObject)?.toMutableMap() ?: LinkedHashMap()
            locOut["label"] = JsonPrimitive(e.location.label)
            locOut["lat"] = e.location.lat?.let { JsonPrimitive(it) } ?: JsonNull
            locOut["lng"] = e.location.lng?.let { JsonPrimitive(it) } ?: JsonNull
            out["location"] = JsonObject(locOut)
        }
        return JsonObject(out)
    }
}
