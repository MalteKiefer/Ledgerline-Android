package de.ledgerline.app.ui.workspace.common

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Human-readable byte size, base-1024, one decimal above KB. */
fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}

/** Format a todo `due` value (ISO date or date-time) into a readable string; returns "" for blank/unparseable. */
fun formatDue(due: String): String {
    if (due.isBlank()) return ""
    return try {
        when {
            due.contains('T') && (due.endsWith("Z") || due.contains('+')) ->
                OffsetDateTime.parse(due).toLocalDateTime().format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
            due.contains('T') ->
                LocalDateTime.parse(due).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
            else ->
                LocalDate.parse(due).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
    } catch (_: Exception) {
        due  // fall back to the raw value rather than hiding it
    }
}
