package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- App-specific WebDAV mount password (GET/PUT/DELETE /account/webdav) ----
@Serializable
data class WebDavStatus(
    val enabled: Boolean = false,
    val username: String = "",
    val url: String = "",
)
@Serializable data class WebDavRequest(@SerialName("webdav_password") val webdavPassword: String)

// ---- Browser sessions (GET /account/sessions, DELETE /account/sessions/{id}) ----
@Serializable
data class SessionRow(
    val id: String = "",
    val ip: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("last_active") val lastActive: Long = 0,
    val current: Boolean = false,
)
@Serializable data class SessionsResponse(val sessions: List<SessionRow> = emptyList())

// ---- Owner-side device pairing (approve a NEW device from this one) ----
@Serializable data class DevicePairingCreated(val id: Long = 0, val qr: String = "", @SerialName("expires_at") val expiresAt: String? = null)
@Serializable data class DevicePairingStatus(val status: String = "", @SerialName("device_name") val deviceName: String? = null)

// ---- Paperless-ngx per-user integration ----
@Serializable
data class PaperlessConfig(
    val enabled: Boolean = false,
    val url: String? = null,
    @SerialName("has_token") val hasToken: Boolean = false,
)
@Serializable
data class PaperlessConfigRequest(
    val enabled: Boolean? = null,
    val url: String? = null,
    val token: String? = null, // blank preserves
)
@Serializable data class PaperlessTerm(val id: Int = 0, val name: String = "", val kind: String = "")
@Serializable data class PaperlessTermsResponse(val tags: List<PaperlessTerm> = emptyList(), val correspondents: List<PaperlessTerm> = emptyList(), val types: List<PaperlessTerm> = emptyList())
@Serializable data class PaperlessOk(val ok: Boolean = false, val detail: String? = null)
