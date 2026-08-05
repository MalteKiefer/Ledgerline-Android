package de.ledgerline.app.core.calendar

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.CalendarRepository
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.remote.dto.ReminderRow
import de.ledgerline.app.domain.model.CalendarEvent
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes the upcoming calendar reminder fire-times and (a) registers the opaque set with the
 * server queue (push when the app is closed) and (b) schedules local on-device alarms (opt-in).
 *
 * Extracted from the Calendar ViewModel so the SAME scheduling runs on background sync — otherwise a
 * reminder created on the web only got a local alarm once the user opened the Calendar screen.
 */
@Singleton
class CalendarReminderScheduler @Inject constructor(
    private val repo: CalendarRepository,
    private val notifier: CalendarNotifier,
    private val settings: SettingsStore,
    private val vaultKeyHolder: VaultKeyHolder,
) {
    /** Reschedule from the current (or freshly loaded) calendar store. No-op while locked. */
    suspend fun rescheduleFromStore() {
        if (vaultKeyHolder.get() == null) return
        val store = repo.cached.value.value
            ?: (repo.load() as? Outcome.Ok)?.value
            ?: return
        schedule(store.manifest.events)
    }

    /** Register server queue + (opt-in) local alarms for [events]. */
    suspend fun schedule(events: List<CalendarEvent>) {
        val now = LocalDateTime.now()
        val today = LocalDate.now()
        val horizon = today.plusDays(90)
        val rows = ArrayList<ReminderRow>()
        val local = ArrayList<LocalReminder>()
        outer@ for (e in events) {
            if (e.reminders.isEmpty() || e.status == "cancelled") continue
            val days = if (e.rrule.isBlank()) listOfNotNull(dateOf(e.start))
            else CalendarRecurrence.occurrences(e.start, e.rrule, e.exdates, today, horizon)
            for (day in days) {
                if (day.isAfter(horizon)) continue
                val startTime = if (!e.allDay && e.start.contains('T')) e.start.substringAfter('T').take(5) else "00:00"
                val startDt = runCatching { LocalDateTime.parse("${day}T$startTime") }.getOrNull() ?: day.atStartOfDay()
                for (mins in e.reminders) {
                    val fire = startDt.minusMinutes(mins.toLong())
                    if (fire.isBefore(now)) continue
                    val instant = fire.atZone(ZoneId.systemDefault()).toInstant()
                    rows.add(ReminderRow(e.id, if (e.rrule.isNotBlank()) day.toString() else null, DateTimeFormatter.ISO_INSTANT.format(instant)))
                    local.add(LocalReminder(instant.toEpochMilli(), e.title.ifBlank { "—" }))
                    if (rows.size >= 2000) break@outer
                }
            }
        }
        repo.registerReminders(rows)
        if (settings.calendarNotificationsEnabled.first()) notifier.schedule(local) else notifier.cancelAll()
    }

    private fun dateOf(v: String): LocalDate? {
        if (v.isBlank()) return null
        return try {
            when {
                v.length >= 10 && v[4] == '-' && (v.length == 10 || v[10] == 'T' || v[10] == ' ') -> LocalDate.parse(v.substring(0, 10))
                v.contains('T') && (v.endsWith("Z") || v.contains('+')) -> OffsetDateTime.parse(v).toLocalDate()
                else -> LocalDate.parse(v.substring(0, 10))
            }
        } catch (_: Exception) {
            null
        }
    }
}
