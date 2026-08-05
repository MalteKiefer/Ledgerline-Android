package de.ledgerline.app.core.calendar

import de.ledgerline.app.domain.model.CalendarEvent

/**
 * Virtual (read-only) calendar feeds — birthdays/anniversaries from ZK contacts + public holidays.
 * Byte-near to the web `shared/calendar-feeds.js`. Feed events are GENERATED for the visible year
 * span, never stored; they use the synthetic calendarIds [BIRTHDAYS] / [HOLIDAYS].
 */
object CalendarFeeds {
    const val BIRTHDAYS = "birthdays"
    const val HOLIDAYS = "holidays"
    const val COLOR_BIRTHDAYS = "#d1607e"
    const val COLOR_HOLIDAYS = "#59ad6b"

    /** A contact reduced to what the birthday feed needs. */
    data class FeedContact(val id: String, val name: String, val bday: String, val anniversary: String)

    private val MD = Regex("""^\d{4}-(\d{2})-(\d{2})$""")
    private val MD_SHORT = Regex("""^(\d{2})-(\d{2})$""")

    private fun monthDay(date: String): String {
        MD.find(date)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}" }
        MD_SHORT.find(date)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}" }
        return ""
    }

    fun birthdayEvents(contacts: List<FeedContact>, startYear: Int, endYear: Int): List<CalendarEvent> {
        val out = ArrayList<CalendarEvent>()
        for (c in contacts) {
            for ((field, value) in listOf("bday" to c.bday, "anniversary" to c.anniversary)) {
                val md = monthDay(value)
                if (!Regex("""^\d{2}-\d{2}$""").matches(md)) continue
                if (c.name.isBlank()) continue
                for (y in startYear..endYear) {
                    val date = "%04d-%s".format(y, md)
                    out.add(
                        CalendarEvent(
                            id = "bday-${c.id}-$field-$y", calendarId = BIRTHDAYS, title = c.name,
                            allDay = true, start = date, end = date, status = "confirmed",
                        ),
                    )
                }
            }
        }
        return out
    }

    fun holidayEvents(country: String, startYear: Int, endYear: Int): List<CalendarEvent> {
        val out = ArrayList<CalendarEvent>()
        for (y in startYear..endYear) {
            for (h in Holidays.computeHolidays(country, y)) {
                out.add(
                    CalendarEvent(
                        id = "hol-$country-${h.date}", calendarId = HOLIDAYS, title = h.name,
                        allDay = true, start = h.date.toString(), end = h.date.toString(), status = "confirmed",
                    ),
                )
            }
        }
        return out
    }
}
