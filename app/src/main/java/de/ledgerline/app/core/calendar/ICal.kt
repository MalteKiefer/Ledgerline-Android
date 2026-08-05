package de.ledgerline.app.core.calendar

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * iCalendar (RFC-5545) import/export for the ZK calendar, byte-near to the web `shared/ical.js`.
 * Times are floating wall-clock (matching how events are stored/displayed); UTC (`Z`) values are
 * converted to local wall-clock, TZID is taken as-is. Pure; the .ics never touches the server for
 * import/export (subscriptions fetch via the SSRF-guarded /calendar/ics-fetch proxy).
 */
object ICal {
    data class ParsedEvent(
        val title: String = "",
        val description: String = "",
        val locationLabel: String = "",
        val allDay: Boolean = false,
        val start: String = "",
        val end: String = "",
        val rrule: String = "",
        val exdates: List<String> = emptyList(),
        val reminders: List<Int> = emptyList(),
        val uid: String = "",
    )

    private fun unfold(text: String): String = text.replace("\r\n\t", "").replace("\r\n ", "").replace("\n\t", "").replace("\n ", "")

    private data class Line(val name: String, val params: Map<String, String>, val value: String)

    private fun parseLine(line: String): Line? {
        val colon = line.indexOf(':')
        if (colon < 0) return null
        val left = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val segs = left.split(';')
        val params = HashMap<String, String>()
        for (i in 1 until segs.size) {
            val eq = segs[i].indexOf('=')
            if (eq > 0) params[segs[i].substring(0, eq).uppercase()] = segs[i].substring(eq + 1)
        }
        return Line(segs[0].uppercase(), params, value)
    }

    private fun unescape(v: String) = v.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
    private fun escape(v: String?) = (v ?: "").replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
    private fun pad(n: Int) = n.toString().padStart(2, '0')

    private data class DateVal(val iso: String, val allDay: Boolean)

    private val DATE_ONLY = Regex("""^(\d{4})(\d{2})(\d{2})""")
    private val DATETIME = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})?(Z)?$""")

    private fun parseDateValue(value: String, params: Map<String, String>): DateVal? {
        val isDate = params["VALUE"]?.uppercase() == "DATE" || Regex("""^\d{8}$""").matches(value)
        if (isDate) {
            val m = DATE_ONLY.find(value) ?: return null
            return DateVal("${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}", true)
        }
        val m = DATETIME.find(value) ?: return null
        var y = m.groupValues[1]; var mo = m.groupValues[2]; var d = m.groupValues[3]
        var hh = m.groupValues[4]; var mm = m.groupValues[5]
        val z = m.groupValues[7]
        if (z == "Z") {
            val local = LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), hh.toInt(), mm.toInt())
                .atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault())
            y = local.year.toString(); mo = pad(local.monthValue); d = pad(local.dayOfMonth)
            hh = pad(local.hour); mm = pad(local.minute)
        }
        return DateVal("$y-$mo-${d}T$hh:$mm", false)
    }

    /** TRIGGER (-PT15M / -PT1H / -P1D / PT0S) → minutesBefore. */
    private fun parseTrigger(value: String): Int? {
        val m = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""").find(value) ?: return null
        val days = m.groupValues[1].toIntOrNull() ?: 0
        val hours = m.groupValues[2].toIntOrNull() ?: 0
        val mins = m.groupValues[3].toIntOrNull() ?: 0
        return days * 1440 + hours * 60 + mins
    }

    fun parseIcs(text: String): List<ParsedEvent> {
        val lines = unfold(text).split(Regex("\r?\n"))
        val out = ArrayList<ParsedEvent>()
        var cur: MutableMap<String, Any?>? = null
        var inAlarm = false
        var alarmTrigger: Int? = null
        val exdates = ArrayList<String>()
        val reminders = ArrayList<Int>()
        for (raw in lines) {
            val line = parseLine(raw) ?: continue
            if (line.name == "BEGIN" && line.value == "VEVENT") { cur = HashMap(); exdates.clear(); reminders.clear(); continue }
            if (line.name == "END" && line.value == "VEVENT") {
                cur?.let { c ->
                    val start = c["start"] as? String ?: ""
                    out.add(
                        ParsedEvent(
                            title = c["title"] as? String ?: "",
                            description = c["description"] as? String ?: "",
                            locationLabel = c["location"] as? String ?: "",
                            allDay = c["allDay"] as? Boolean ?: false,
                            start = start,
                            end = (c["end"] as? String) ?: start,
                            rrule = c["rrule"] as? String ?: "",
                            exdates = exdates.toList(),
                            reminders = reminders.toList(),
                            uid = c["uid"] as? String ?: "",
                        ),
                    )
                }
                cur = null; continue
            }
            if (cur == null) continue
            if (line.name == "BEGIN" && line.value == "VALARM") { inAlarm = true; alarmTrigger = null; continue }
            if (line.name == "END" && line.value == "VALARM") { alarmTrigger?.let { reminders.add(it) }; inAlarm = false; continue }
            if (inAlarm) { if (line.name == "TRIGGER") alarmTrigger = parseTrigger(line.value); continue }
            when (line.name) {
                "SUMMARY" -> cur["title"] = unescape(line.value)
                "DESCRIPTION" -> cur["description"] = unescape(line.value)
                "LOCATION" -> cur["location"] = unescape(line.value)
                "UID" -> cur["uid"] = line.value
                "RRULE" -> cur["rrule"] = line.value.trim()
                "DTSTART" -> parseDateValue(line.value, line.params)?.let { cur["start"] = it.iso; cur["allDay"] = it.allDay }
                "DTEND" -> parseDateValue(line.value, line.params)?.let { cur["end"] = it.iso }
                "EXDATE" -> for (v in line.value.split(',')) parseDateValue(v, line.params)?.let { exdates.add(it.iso.take(10)) }
            }
        }
        return out.filter { it.title.isNotBlank() || it.start.isNotBlank() }
    }

    // ---- export ----

    private fun foldLine(line: String): String {
        if (line.length <= 75) return line
        val sb = StringBuilder(line.substring(0, 75))
        var rest = line.substring(75)
        while (rest.length > 74) { sb.append("\r\n ").append(rest.substring(0, 74)); rest = rest.substring(74) }
        sb.append("\r\n ").append(rest)
        return sb.toString()
    }

    private fun icsDate(iso: String, allDay: Boolean): Pair<String, String> {
        if (allDay) { val d = iso.take(10).replace("-", ""); return ";VALUE=DATE" to d }
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})""").find(iso)
            ?: return ";VALUE=DATE" to iso.take(10).replace("-", "")
        return "" to "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}T${m.groupValues[4]}${m.groupValues[5]}00"
    }

    private fun triggerFor(minutesBefore: Int): String = when {
        minutesBefore == 0 -> "PT0M"
        minutesBefore % 1440 == 0 -> "-P${minutesBefore / 1440}D"
        minutesBefore % 60 == 0 -> "-PT${minutesBefore / 60}H"
        else -> "-PT${minutesBefore}M"
    }

    /** Event export shape (subset of CalendarEvent the writer needs). */
    data class ExportEvent(
        val id: String, val title: String, val start: String, val end: String, val allDay: Boolean,
        val rrule: String = "", val exdates: List<String> = emptyList(), val locationLabel: String = "",
        val description: String = "", val reminders: List<Int> = emptyList(), val uid: String = "",
    )

    fun buildIcs(events: List<ExportEvent>, calendarName: String = "Ledgerline"): String {
        val out = ArrayList<String>()
        out += listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Ledgerline//Calendar//EN", "CALSCALE:GREGORIAN", "X-WR-CALNAME:${escape(calendarName)}")
        for (ev in events) {
            val (sp, sv) = icsDate(ev.start, ev.allDay)
            val (ep, evv) = icsDate(ev.end.ifBlank { ev.start }, ev.allDay)
            out += "BEGIN:VEVENT"
            out += "UID:${ev.uid.ifBlank { ev.id.ifBlank { kotlin.math.abs((ev.title + ev.start).hashCode()).toString() } }}@ledgerline"
            val stamp = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
            out += "DTSTAMP:$stamp"
            out += foldLine("SUMMARY:${escape(ev.title)}")
            out += "DTSTART$sp:$sv"
            out += "DTEND$ep:$evv"
            if (ev.rrule.isNotBlank()) out += "RRULE:${ev.rrule}"
            if (ev.exdates.isNotEmpty()) out += "EXDATE;VALUE=DATE:${ev.exdates.joinToString(",") { it.replace("-", "") }}"
            if (ev.locationLabel.isNotBlank()) out += foldLine("LOCATION:${escape(ev.locationLabel)}")
            if (ev.description.isNotBlank()) out += foldLine("DESCRIPTION:${escape(ev.description)}")
            for (r in ev.reminders) out += listOf("BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:${triggerFor(r)}", "DESCRIPTION:${escape(ev.title.ifBlank { "Reminder" })}", "END:VALARM")
            out += "END:VEVENT"
        }
        out += "END:VCALENDAR"
        return out.joinToString("\r\n") + "\r\n"
    }
}
