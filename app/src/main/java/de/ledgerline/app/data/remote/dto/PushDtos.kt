package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Body for `POST /api/v1/device/push-endpoint` — hands the device's UnifiedPush endpoint URL to
 * the server so it can deliver notifications to this device. Bound server-side to the calling
 * device token. See the server design spec (`docs/superpowers/specs/…-server-design.md`).
 */
@Serializable
data class PushEndpointRequest(val endpoint: String)

/**
 * The JSON the server pushes through the endpoint. Display-ready (post-ZK; the server holds
 * plaintext). Decoded leniently — unknown keys ignored, missing keys keep defaults.
 */
@Serializable
data class PushPayload(
    val id: Long = 0,
    val category: String = "",
    val level: String = "info",     // info | success | warning | error
    val title: String = "",
    val body: String? = null,
)
