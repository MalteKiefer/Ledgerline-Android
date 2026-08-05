package de.ledgerline.app.core.calendar

import de.ledgerline.app.domain.model.CalendarRecordCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarLogicTest {

    // ---- Recurrence ----

    @Test fun weekly_byday_expands_selected_weekdays() {
        // Anchor Mon 2026-08-03; MO,WE weekly through the month.
        val occ = CalendarRecurrence.occurrences(
            startIso = "2026-08-03T09:00", rrule = "FREQ=WEEKLY;BYDAY=MO,WE", exdates = emptyList(),
            rangeStart = LocalDate.parse("2026-08-03"), rangeEnd = LocalDate.parse("2026-08-16"),
        )
        assertEquals(
            listOf("2026-08-03", "2026-08-05", "2026-08-10", "2026-08-12"),
            occ.take(4).map { it.toString() },
        )
    }

    @Test fun daily_interval_and_count() {
        val occ = CalendarRecurrence.occurrences(
            "2026-08-01", "FREQ=DAILY;INTERVAL=2;COUNT=3", emptyList(),
            LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"),
        )
        assertEquals(listOf("2026-08-01", "2026-08-03", "2026-08-05"), occ.map { it.toString() })
    }

    @Test fun until_bounds_and_exdate_skips() {
        val occ = CalendarRecurrence.occurrences(
            "2026-08-01", "FREQ=DAILY;UNTIL=20260804T235959Z", listOf("2026-08-02"),
            LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"),
        )
        assertEquals(listOf("2026-08-01", "2026-08-03", "2026-08-04"), occ.map { it.toString() })
    }

    @Test fun build_parse_roundtrip() {
        val s = CalendarRecurrence.build("WEEKLY", 2, listOf("MO", "FR"), "count", 5, "")
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR;COUNT=5", s)
        val r = CalendarRecurrence.parse(s)!!
        assertEquals("WEEKLY", r.freq); assertEquals(2, r.interval); assertEquals(5, r.count)
    }

    // ---- Holidays ----

    @Test fun holidays_easter_and_nth_weekday() {
        val de = Holidays.computeHolidays("DE", 2026).associate { it.name to it.date.toString() }
        // Easter Sunday 2026 = 2026-04-05 → Karfreitag 04-03, Ostermontag 04-06.
        assertEquals("2026-04-03", de["Karfreitag"])
        assertEquals("2026-04-06", de["Ostermontag"])
        assertEquals("2026-01-01", de["Neujahr"])
        val us = Holidays.computeHolidays("US", 2026).associate { it.name to it.date.toString() }
        // Thanksgiving = 4th Thursday of November 2026 = 2026-11-26.
        assertEquals("2026-11-26", us["Thanksgiving"])
    }

    // ---- iCal ----

    @Test fun ical_parse_then_build_roundtrips_core_fields() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x@y\r\nSUMMARY:Meeting\r\n" +
            "DTSTART:20260803T090000\r\nDTEND:20260803T100000\r\nRRULE:FREQ=WEEKLY\r\n" +
            "LOCATION:Room 1\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val events = ICal.parseIcs(ics)
        assertEquals(1, events.size)
        val e = events.first()
        assertEquals("Meeting", e.title)
        assertEquals("2026-08-03T09:00", e.start)
        assertEquals("FREQ=WEEKLY", e.rrule)
        assertEquals("Room 1", e.locationLabel)
        val built = ICal.buildIcs(
            listOf(ICal.ExportEvent(id = "1", title = e.title, start = e.start, end = e.end, allDay = false, rrule = e.rrule, locationLabel = e.locationLabel)),
        )
        assertTrue(built.contains("SUMMARY:Meeting"))
        assertTrue(built.contains("RRULE:FREQ=WEEKLY"))
        assertTrue(built.contains("DTSTART:20260803T090000"))
    }

    // ---- Codec no field loss ----

    @Test fun codec_preserves_unknown_event_field() {
        val root = Json.parseToJsonElement(
            """{"v":3,"calendars":[{"id":"c1","name":"Cal","color":"#7066f5","isDefault":true}],
               "events":[{"id":"e1","calendarId":"c1","title":"T","start":"2026-08-03","allDay":true,"customField":"keepme"}],
               "settings":{"birthdays":true}}""".trimIndent(),
        ) as JsonObject
        val m = CalendarRecordCodec.decodeManifest(root)
        val out = CalendarRecordCodec.encodeManifest(m)
        val ev = (out["events"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject
        assertEquals("keepme", (ev["customField"] as kotlinx.serialization.json.JsonPrimitive).content)
        // settings preserved verbatim.
        assertTrue((out["settings"] as JsonObject).containsKey("birthdays"))
    }
}
