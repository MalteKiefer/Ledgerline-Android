package de.ledgerline.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.CalendarCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.data.CalendarRepository
import de.ledgerline.app.domain.model.CalendarEvent
import de.ledgerline.app.domain.model.CalendarModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import javax.inject.Inject

data class CalendarUi(
    val loading: Boolean = true,
    val error: Boolean = false,
    val calendars: List<CalendarModel> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
)

/**
 * Drives the Calendar module: reads the decrypted `store/calendar` manifest from [CalendarCache]
 * and exposes a month grid + per-day event lookup. First pass is display-only; recurring events
 * (rrule) show on their start day with a repeat marker until full RFC-5545 expansion lands.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repo: CalendarRepository,
    cache: CalendarCache,
) : ViewModel() {

    val ui: StateFlow<CalendarUi> = cache.value
        .map { store ->
            if (store == null) CalendarUi(loading = true)
            else CalendarUi(false, false, store.manifest.calendars, store.manifest.events)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CalendarUi(loading = true))

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month

    private val _selectedDay = MutableStateFlow(LocalDate.now())
    val selectedDay: StateFlow<LocalDate> = _selectedDay

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        if (repo.load() is Outcome.Err) { /* cache flow keeps the last good state; UI shows it */ }
    }

    // ---- Mutations (create/edit/delete) ----

    /** Insert or update an event; creates a default calendar on first use if none exists. */
    fun saveEvent(
        id: String?,
        calendarId: String,
        title: String,
        description: String,
        allDay: Boolean,
        start: String,
        end: String,
        tz: String,
        location: de.ledgerline.app.domain.model.EventLocation?,
        rrule: String = "",
    ) = viewModelScope.launch {
        repo.save { m ->
            var cals = m.calendars
            var cid = calendarId
            if (cid.isBlank() || cals.none { it.id == cid }) {
                cid = cals.firstOrNull { it.isDefault }?.id ?: cals.firstOrNull()?.id ?: ""
                if (cid.isBlank()) {
                    val c = de.ledgerline.app.domain.model.CalendarModel(
                        id = de.ledgerline.app.core.Ids.newId(), name = "Kalender", color = "#7066f5", isDefault = true,
                    )
                    cals = cals + c
                    cid = c.id
                }
            }
            val existing = m.events.firstOrNull { it.id == id }
            val ev = (existing ?: de.ledgerline.app.domain.model.CalendarEvent(id = de.ledgerline.app.core.Ids.newId(), calendarId = cid, title = title, start = start)).copy(
                calendarId = cid, title = title, description = description, allDay = allDay,
                start = start, end = end, tz = tz, location = location, rrule = rrule, status = "confirmed",
            )
            val events = if (existing != null) m.events.map { if (it.id == ev.id) ev else it } else m.events + ev
            m.copy(calendars = cals, events = events)
        }
    }

    fun deleteEvent(id: String) = viewModelScope.launch {
        repo.save { m -> m.copy(events = m.events.filterNot { it.id == id }) }
    }

    fun defaultCalendarId(): String =
        ui.value.calendars.firstOrNull { it.isDefault }?.id ?: ui.value.calendars.firstOrNull()?.id ?: ""

    fun prevMonth() { _month.value = _month.value.minusMonths(1) }
    fun nextMonth() { _month.value = _month.value.plusMonths(1) }
    fun goToday() { _month.value = YearMonth.now(); _selectedDay.value = LocalDate.now() }
    fun selectDay(d: LocalDate) { _selectedDay.value = d }

    /** Hex colour of the event's calendar (falls back to the brand indigo). */
    fun colorFor(calendarId: String): String =
        ui.value.calendars.firstOrNull { it.id == calendarId }?.color ?: "#7066f5"

    /** Display name of the event's calendar, or "" if unknown. */
    fun calendarName(calendarId: String): String =
        ui.value.calendars.firstOrNull { it.id == calendarId }?.name ?: ""

    /** Days (yyyy-MM-dd) of every per-occurrence override, keyed base|day, so masters skip them. */
    private fun overrideKeys(events: List<CalendarEvent>): Set<String> =
        events.filter { it.recurrenceId.isNotBlank() && it.overrideOf.isNotBlank() }
            .mapTo(HashSet()) { "${it.overrideOf}|${it.recurrenceId.take(10)}" }

    private fun spanDays(e: CalendarEvent): Long {
        val s = dateOf(e.start) ?: return 0
        val end = dateOf(e.end) ?: s
        return maxOf(0, de.ledgerline.app.core.calendar.CalendarRecurrence.daysBetween(s, end))
    }

    /** Shift a recurring master to a concrete occurrence starting on [day] (keeps wall-clock time). */
    private fun occurrenceOf(e: CalendarEvent, day: LocalDate): CalendarEvent {
        val startTime = if (!e.allDay && e.start.contains('T')) e.start.substringAfter('T').take(5) else ""
        val endTime = if (!e.allDay && e.end.contains('T')) e.end.substringAfter('T').take(5) else startTime
        val endDay = day.plusDays(spanDays(e))
        val start = if (e.allDay || startTime.isEmpty()) day.toString() else "${day}T$startTime"
        val end = if (e.allDay || endTime.isEmpty()) endDay.toString() else "${endDay}T$endTime"
        return e.copy(start = start, end = end, rrule = "", recurrenceId = day.toString())
    }

    /** Events touching [date] (recurrences expanded, overrides applied), all-day first then by time. */
    fun eventsForDay(date: LocalDate): List<CalendarEvent> {
        val events = ui.value.events
        val overrides = overrideKeys(events)
        val out = ArrayList<CalendarEvent>()
        for (e in events) {
            if (e.status == "cancelled") continue
            if (e.rrule.isBlank()) {
                if (covers(e, date)) out.add(e)
            } else {
                val span = spanDays(e)
                val occ = de.ledgerline.app.core.calendar.CalendarRecurrence.occurrences(
                    e.start, e.rrule, e.exdates, date.minusDays(span), date,
                )
                for (day in occ) {
                    if (overrides.contains("${e.id}|$day")) continue
                    val syn = occurrenceOf(e, day)
                    if (covers(syn, date)) out.add(syn)
                }
            }
        }
        return out.sortedWith(compareByDescending<CalendarEvent> { it.allDay }.thenBy { it.start })
    }

    /** Days in [month] that have at least one event — drives the grid dots. */
    fun daysWithEvents(month: YearMonth): Set<LocalDate> {
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        val events = ui.value.events
        val overrides = overrideKeys(events)
        val out = HashSet<LocalDate>()
        for (e in events) {
            if (e.status == "cancelled") continue
            val span = spanDays(e)
            if (e.rrule.isBlank()) {
                val s = dateOf(e.start) ?: continue
                val end = dateOf(e.end) ?: s
                var d = if (s.isBefore(first)) first else s
                val stop = if (end.isAfter(last)) last else end
                while (!d.isAfter(stop)) { out.add(d); d = d.plusDays(1) }
            } else {
                val occ = de.ledgerline.app.core.calendar.CalendarRecurrence.occurrences(
                    e.start, e.rrule, e.exdates, first.minusDays(span), last,
                )
                for (day in occ) {
                    if (overrides.contains("${e.id}|$day")) continue
                    var d = if (day.isBefore(first)) first else day
                    val stop = minOf(day.plusDays(span), last)
                    while (!d.isAfter(stop)) { out.add(d); d = d.plusDays(1) }
                }
            }
        }
        return out
    }

    private fun covers(e: CalendarEvent, date: LocalDate): Boolean {
        val s = dateOf(e.start) ?: return false
        val end = dateOf(e.end) ?: s
        return !date.isBefore(s) && !date.isAfter(end)
    }

    companion object {
        /** LocalDate from an ISO datetime or a `yyyy-MM-dd` string; null if unparseable/blank. */
        fun dateOf(v: String): LocalDate? {
            if (v.isBlank()) return null
            return try {
                when {
                    v.length >= 10 && v[4] == '-' && (v.length == 10 || v[10] == 'T' || v[10] == ' ') ->
                        LocalDate.parse(v.substring(0, 10))
                    v.contains('T') && (v.endsWith("Z") || v.contains('+')) ->
                        OffsetDateTime.parse(v).toLocalDate()
                    else -> LocalDate.parse(v.substring(0, 10))
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
