package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

/** `GET /notifications` → up to 30 recent in-app notifications + an unread count (ETag/304). */
@Serializable
data class NotificationsResponse(
    val unread: Int = 0,
    val items: List<NotificationDto> = emptyList(),
)

@Serializable
data class NotificationDto(
    val id: Long = 0,
    val level: String = "info",     // info | success | warning | error (server-defined)
    val category: String = "",
    val title: String = "",
    val body: String? = null,
    val read: Boolean = false,
    val at: String? = null,         // ISO-8601 timestamp, nullable
)
