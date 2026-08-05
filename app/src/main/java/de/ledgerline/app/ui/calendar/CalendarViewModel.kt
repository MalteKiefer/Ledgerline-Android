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
                start = start, end = end, tz = tz, location = location, status = "confirmed",
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

    /** Events touching [date], sorted all-day first then by start time. */
    fun eventsForDay(date: LocalDate): List<CalendarEvent> =
        ui.value.events
            .filter { it.status != "cancelled" && covers(it, date) }
            .sortedWith(compareByDescending<CalendarEvent> { it.allDay }.thenBy { it.start })

    /** Days in [month] that have at least one (non-cancelled) event — drives the grid dots. */
    fun daysWithEvents(month: YearMonth): Set<LocalDate> {
        val out = HashSet<LocalDate>()
        val first = month.atDay(1)
        val last = month.atEndOfMonth()
        for (e in ui.value.events) {
            if (e.status == "cancelled") continue
            val s = dateOf(e.start) ?: continue
            val end = dateOf(e.end) ?: s
            // Clamp the event's span to the visible month and mark each covered day.
            var d = if (s.isBefore(first)) first else s
            val stop = if (end.isAfter(last)) last else end
            while (!d.isAfter(stop)) { out.add(d); d = d.plusDays(1) }
            // A recurring event whose first occurrence is before this month still shows via its
            // start day only (no expansion yet) — mark the start if it lands in-month.
            if (e.rrule.isNotBlank() && s in first..last) out.add(s)
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
