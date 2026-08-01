package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

/** `POST /contacts/notify` — relay a birthday/anniversary to the user's enabled channels. */
@Serializable data class ContactNotifyRequest(
    val kind: String,   // birthday | anniversary
    val title: String,
    val body: String,
)

@Serializable data class ContactNotifyResponse(val forwarded: Boolean = false)
