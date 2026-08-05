package de.ledgerline.app.core.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Recurrence (RFC-5545 RRULE) expansion for calendar events, byte-near to the web
 * `shared/calendar-rrule.js` but covering exactly the subset the editor emits:
 * FREQ (DAILY/WEEKLY/MONTHLY/YEARLY), INTERVAL, BYDAY (weekly), COUNT, UNTIL.
 *
 * Wall-clock floating: an occurrence keeps the master event's local wall-clock time; no timezone
 * conversion is applied (wall-clock in = wall-clock out). All-day events use date-only ISO.
 */
object CalendarRecurrence {
    val FREQS = listOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")
    val WEEKDAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    private val DOW = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )

    data class Rule(
        val freq: String,
        val interval: Int = 1,
        val byday: List<String> = emptyList(),
        val count: Int? = null,
        val until: LocalDate? = null,
    )

    fun parse(rrule: String): Rule? {
        if (rrule.isBlank()) return null
        var freq = ""
        var interval = 1
        var byday = emptyList<String>()
        var count: Int? = null
        var until: LocalDate? = null
        for (part in rrule.split(';')) {
            val kv = part.split('=', limit = 2)
            if (kv.size != 2) continue
            when (kv[0].uppercase()) {
                "FREQ" -> freq = kv[1].uppercase()
                "INTERVAL" -> interval = kv[1].toIntOrNull()?.coerceAtLeast(1) ?: 1
                "BYDAY" -> byday = kv[1].split(',').map { it.uppercase() }.filter { it in WEEKDAYS }
                "COUNT" -> count = kv[1].toIntOrNull()
                "UNTIL" -> until = runCatching { LocalDate.parse(kv[1].take(8).let { "${it.take(4)}-${it.substring(4, 6)}-${it.substring(6, 8)}" }) }.getOrNull()
            }
        }
        if (freq !in FREQS) return null
        return Rule(freq, interval, byday, count, until)
    }

    /** Editor options → RRULE string (mirror of `buildRRuleString`); "" for no recurrence. */
    fun build(freq: String, interval: Int, byday: List<String>, ends: String, count: Int, until: String): String {
        if (freq.isBlank() || freq == "none") return ""
        val bits = mutableListOf("FREQ=$freq")
        if (interval > 1) bits += "INTERVAL=$interval"
        if (freq == "WEEKLY" && byday.isNotEmpty()) bits += "BYDAY=${byday.joinToString(",")}"
        if (ends == "count" && count > 0) bits += "COUNT=$count"
        if (ends == "until" && until.isNotBlank()) bits += "UNTIL=${until.replace("-", "")}T235959Z"
        return bits.joinToString(";")
    }

    private data class Parts(val date: LocalDate, val time: String)

    private fun startParts(startIso: String): Parts {
        val date = LocalDate.parse(startIso.take(10))
        val time = if (startIso.length > 10 && startIso.contains('T')) startIso.substringAfter('T').take(5) else ""
        return Parts(date, time)
    }

    private fun iso(date: LocalDate, time: String, allDay: Boolean): String =
        if (allDay || time.isEmpty()) date.toString() else "${date}T$time"

    /**
     * Occurrence START days of [rrule] anchored at [startIso], intersecting [rangeStart, rangeEnd]
     * (inclusive). Skips [exdates] (day granularity). Bounded + safe on very old master events.
     */
    fun occurrences(
        startIso: String,
        rrule: String,
        exdates: List<String>,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
    ): List<LocalDate> {
        val rule = parse(rrule) ?: return emptyList()
        val anchor = startParts(startIso).date
        val ex = exdates.map { it.take(10) }.toHashSet()
        val out = ArrayList<LocalDate>()
        val cap = 5000
        var produced = 0

        fun emitIfInRange(d: LocalDate): Boolean {
            // returns false to signal "past rangeEnd, stop"
            if (d.isAfter(rangeEnd)) return false
            if (rule.until != null && d.isAfter(rule.until)) return false
            if (!d.isBefore(rangeStart) && !ex.contains(d.toString())) out.add(d)
            return true
        }

        when (rule.freq) {
            "WEEKLY" -> {
                val days = if (rule.byday.isEmpty()) listOf(anchor.dayOfWeek) else rule.byday.mapNotNull { DOW[it] }
                // Week 0 = the ISO week (Mon-based) containing the anchor.
                var weekStart = anchor.with(DayOfWeek.MONDAY)
                var occIndexForCount = 0
                var iterations = 0
                while (iterations++ < cap) {
                    var anyEmitted = false
                    var pastEnd = false
                    for (dow in days.sortedBy { it.value }) {
                        val d = weekStart.with(dow)
                        if (d.isBefore(anchor)) continue // don't emit before the series start
                        if (rule.count != null && occIndexForCount >= rule.count) { pastEnd = true; break }
                        occIndexForCount++
                        if (!emitIfInRange(d)) { pastEnd = true; break }
                        anyEmitted = true
                    }
                    if (pastEnd) break
                    if (weekStart.isAfter(rangeEnd) && !anyEmitted) break
                    weekStart = weekStart.plusWeeks(rule.interval.toLong())
                    if (rule.count == null && weekStart.isAfter(rangeEnd)) break
                }
            }
            else -> {
                var d = anchor
                while (produced++ < cap) {
                    if (rule.count != null && (produced - 1) >= rule.count) break
                    if (!emitIfInRange(d)) break
                    d = when (rule.freq) {
                        "DAILY" -> d.plusDays(rule.interval.toLong())
                        "MONTHLY" -> d.plusMonths(rule.interval.toLong())
                        "YEARLY" -> d.plusYears(rule.interval.toLong())
                        else -> break
                    }
                    if (rule.count == null && d.isAfter(rangeEnd)) break
                    if (rule.until != null && d.isAfter(rule.until)) break
                }
            }
        }
        return out
    }

    /** Human-readable recurrence summary using caller-supplied freq labels. */
    fun summary(rrule: String, freqLabel: (String) -> String, every: String): String {
        val r = parse(rrule) ?: return ""
        val f = freqLabel(r.freq)
        val sb = StringBuilder(if (r.interval > 1) "$every ${r.interval} $f" else f)
        if (r.freq == "WEEKLY" && r.byday.isNotEmpty()) sb.append(" (${r.byday.joinToString(", ")})")
        r.count?.let { sb.append(" · ${it}×") }
        r.until?.let { sb.append(" · → $it") }
        return sb.toString()
    }

    /** ChronoUnit helper kept for callers computing spans. */
    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)

    /** LocalDateTime helper (used for reminder fire-times). */
    fun localDateTime(startIso: String): LocalDateTime? = runCatching {
        if (startIso.length > 10 && startIso.contains('T')) LocalDateTime.parse(startIso.take(16))
        else LocalDate.parse(startIso.take(10)).atStartOfDay()
    }.getOrNull()
}
