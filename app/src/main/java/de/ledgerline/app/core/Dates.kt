package de.ledgerline.app.core

import de.ledgerline.app.data.DateFormatPref
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Formats the free-form date strings the vault stores (ISO datetimes, `yyyy-MM-dd`,
 * and vCard year-less `--MM-dd` birthdays) into a user-chosen display format. Anything
 * unparseable is returned verbatim, so a hand-typed value is never lost.
 */
object Dates {

    fun format(raw: String, pref: DateFormatPref): String {
        val t = raw.trim()
        if (t.isEmpty()) return t

        // vCard year-less date: "--MM-dd" or "MM-dd".
        Regex("""^-{0,2}(\d{2})-(\d{2})$""").find(t)?.let { m ->
            val mm = m.groupValues[1]
            val dd = m.groupValues[2]
            return when (pref) {
                DateFormatPref.YMD -> "--$mm-$dd"
                DateFormatPref.DMY -> "$dd.$mm."
                DateFormatPref.MDY -> "$mm/$dd"
                DateFormatPref.SYSTEM -> runCatching {
                    LocalDate.of(2000, mm.toInt(), dd.toInt())
                        .format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))
                }.getOrDefault(t)
            }
        }

        val date = parse(t) ?: return t
        val fmt = when (pref) {
            DateFormatPref.SYSTEM -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
            DateFormatPref.DMY -> DateTimeFormatter.ofPattern("dd.MM.yyyy")
            DateFormatPref.YMD -> DateTimeFormatter.ofPattern("yyyy-MM-dd")
            DateFormatPref.MDY -> DateTimeFormatter.ofPattern("MM/dd/yyyy")
        }
        return runCatching { date.format(fmt) }.getOrDefault(t)
    }

    private fun parse(t: String): LocalDate? {
        runCatching { return OffsetDateTime.parse(t).toLocalDate() }
        runCatching { return LocalDateTime.parse(t).toLocalDate() }
        runCatching { return LocalDate.parse(t) }
        return null
    }
}
