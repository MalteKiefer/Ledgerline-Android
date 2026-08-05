package de.ledgerline.app.core.calendar

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Public-holiday computation, byte-near to the web `shared/holidays.js` for the countries the
 * calendar settings expose (openapi enum: DE, AT, CH, GB, US). Easter via Meeus/Jones/Butcher;
 * fixed + Easter-relative + nth-weekday dates per country. Pure; returns [(date, name)] for a year.
 */
object Holidays {
    val COUNTRIES = listOf("DE", "AT", "CH", "GB", "US")

    data class Holiday(val date: LocalDate, val name: String)

    /** Gregorian Easter Sunday. */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    private fun easterOffset(year: Int, days: Int): LocalDate = easterSunday(year).plusDays(days.toLong())

    /** nth (1-based; -1 = last) [weekday] (0=Sun..6=Sat) of [month] in [year]. */
    private fun nthWeekday(year: Int, month: Int, weekday: Int, nth: Int): LocalDate {
        val target = DayOfWeek.of(if (weekday == 0) 7 else weekday)
        return if (nth == -1) {
            var d = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1)
            while (d.dayOfWeek != target) d = d.minusDays(1)
            d
        } else {
            var d = LocalDate.of(year, month, 1)
            while (d.dayOfWeek != target) d = d.plusDays(1)
            d.plusWeeks((nth - 1).toLong())
        }
    }

    private sealed interface Def { val name: String }
    private data class Fixed(val m: Int, val d: Int, override val name: String) : Def
    private data class Easter(val offset: Int, override val name: String) : Def
    private data class Nth(val m: Int, val wd: Int, val nth: Int, override val name: String) : Def

    private val CATALOG: Map<String, List<Def>> = mapOf(
        "DE" to listOf(
            Fixed(1, 1, "Neujahr"), Easter(-2, "Karfreitag"), Easter(1, "Ostermontag"),
            Fixed(5, 1, "Tag der Arbeit"), Easter(39, "Christi Himmelfahrt"), Easter(50, "Pfingstmontag"),
            Fixed(10, 3, "Tag der Deutschen Einheit"), Fixed(12, 25, "1. Weihnachtstag"), Fixed(12, 26, "2. Weihnachtstag"),
        ),
        "AT" to listOf(
            Fixed(1, 1, "Neujahr"), Fixed(1, 6, "Heilige Drei Könige"), Easter(1, "Ostermontag"),
            Fixed(5, 1, "Staatsfeiertag"), Easter(39, "Christi Himmelfahrt"), Easter(50, "Pfingstmontag"),
            Easter(60, "Fronleichnam"), Fixed(8, 15, "Mariä Himmelfahrt"), Fixed(10, 26, "Nationalfeiertag"),
            Fixed(11, 1, "Allerheiligen"), Fixed(12, 8, "Mariä Empfängnis"), Fixed(12, 25, "Christtag"), Fixed(12, 26, "Stefanitag"),
        ),
        "CH" to listOf(
            Fixed(1, 1, "Neujahr"), Easter(-2, "Karfreitag"), Easter(1, "Ostermontag"),
            Easter(39, "Auffahrt"), Easter(50, "Pfingstmontag"), Fixed(8, 1, "Bundesfeier"), Fixed(12, 25, "Weihnachten"),
        ),
        "GB" to listOf(
            Fixed(1, 1, "New Year's Day"), Easter(-2, "Good Friday"), Easter(1, "Easter Monday"),
            Fixed(12, 25, "Christmas Day"), Fixed(12, 26, "Boxing Day"),
        ),
        "US" to listOf(
            Fixed(1, 1, "New Year's Day"), Nth(1, 1, 3, "Martin Luther King Jr. Day"), Nth(5, 1, -1, "Memorial Day"),
            Fixed(7, 4, "Independence Day"), Nth(9, 1, 1, "Labor Day"), Nth(11, 4, 4, "Thanksgiving"), Fixed(12, 25, "Christmas Day"),
        ),
    )

    fun computeHolidays(country: String, year: Int): List<Holiday> =
        (CATALOG[country] ?: return emptyList()).mapNotNull { def ->
            val date = runCatching {
                when (def) {
                    is Fixed -> LocalDate.of(year, def.m, def.d)
                    is Easter -> easterOffset(year, def.offset)
                    is Nth -> nthWeekday(year, def.m, def.wd, def.nth)
                }
            }.getOrNull()
            date?.let { Holiday(it, def.name) }
        }
}
