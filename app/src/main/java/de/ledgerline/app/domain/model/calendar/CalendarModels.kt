package de.ledgerline.app.domain.model.calendar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Calendar/task-list models for the VTODO ("Aufgaben") feature. The Android app deliberately maps
 * only the **task list** slice of the server's calendar module (not events) — a VTODO calendar is a
 * task list (Apple Reminders / Tasks.org). Decoding is lenient (`ignoreUnknownKeys`); writes are sent
 * as free-form JSON so the repository controls exactly which `CalendarTodoInput` fields go out.
 */
@Serializable
data class Calendar(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val color: String? = null,
    val kind: String = "normal",
    val component: String = "VEVENT",
    val owned: Boolean = true,
)

@Serializable data class CalendarDataResponse(val calendars: List<Calendar> = emptyList())

/** A task (VTODO). Status ∈ NEEDS-ACTION / IN-PROCESS / COMPLETED / CANCELLED. */
@Serializable
data class CalendarTodo(
    val id: String = "",
    val calendar: String = "",
    val uid: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val status: String = "NEEDS-ACTION",
    val priority: Int? = null,
    @SerialName("percent_complete") val percentComplete: Int? = null,
    val due: String? = null,
    val dtstart: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val rrule: String? = null,
    val categories: List<String> = emptyList(),
    @SerialName("related_to") val relatedTo: String? = null,
    @SerialName("alarm_minutes_before") val alarmMinutes: Int? = null,
    val sequence: Int = 0,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val color: String? = null,
    val etag: String = "",
    @SerialName("next_due") val nextDue: String? = null,
) {
    val done: Boolean get() = status == "COMPLETED"
}

@Serializable data class TodosResponse(val todos: List<CalendarTodo> = emptyList())

@Serializable data class TodoCreated(val id: String? = null)

@Serializable data class TodoImportResult(val created: Int = 0, val updated: Int = 0, val skipped: Int = 0)
