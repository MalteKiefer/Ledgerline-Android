package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

/** Body of GET /calendar/ics-fetch (SSRF-guarded public iCal proxy). */
@Serializable data class IcsFetchResponse(val ics: String = "")

/** One opaque reminder fire-time row (PUT /calendar/reminders). No event content leaves the device. */
@Serializable data class ReminderRow(
    val event_id: String,
    val recurrence_id: String? = null,
    val remind_at: String,
)

@Serializable data class RemindersRequest(val reminders: List<ReminderRow>)

@Serializable data class RemindersResponse(val count: Int = 0)
