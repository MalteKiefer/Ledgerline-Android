package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class PairClaimRequest(val code: String, val device_name: String)
@Serializable data class PairClaimResponse(val status: String)

/** `POST /api/v1/auth/pair/collect` — poll for approval with the same one-time code. */
@Serializable data class PairCollectRequest(
    val code: String,
    val install_id: String? = null,
    val app_version: String? = null,
    val os_version: String? = null,
)

@Serializable data class PairPollResponse(
    val status: String,
    val token: String? = null,
    val user: PairedUser? = null,
)

@Serializable data class PairedUser(
    val id: Long? = null,
    val name: String? = null,
    val email: String? = null,
    val locale: String? = null,
)

/** `GET /api/v1/me` — account identity + usage. All fields tolerant/defaulted. */
@Serializable data class MeResponse(
    val user: MeUser,
    val usage: MeUsage? = null,
    /** Remote kill switch: the owner flagged this device to wipe its local state. */
    val wipe: Boolean = false,
    /** Global display preferences (units + clock), mirrored from the server (web `fd490ce3`). */
    val preferences: DisplayPrefsDto? = null,
)

/** Global non-secret display preferences (units + 12/24h clock). Also the `POST /preferences` body. */
@Serializable data class DisplayPrefsDto(
    val distance: String? = null,
    val elevation: String? = null,
    val weight: String? = null,
    val temp: String? = null,
    val glucose: String? = null,
    @SerialName("time_format") val timeFormat: String? = null,
)

@Serializable data class MeUser(
    val id: Long? = null,
    val name: String? = null,
    val email: String? = null,
    val locale: String? = null,
    val groups: List<String> = emptyList(),
)

@Serializable data class MeUsage(
    val files: Long? = null,
    val gallery: Long? = null,
    // Combined effective storage limit in bytes (files + gallery), or null when unlimited (the
    // server returns null whenever either dimension is uncapped → the pool has no finite cap).
    val quota: Long? = null,
)

/** `GET /api/v1/devices` → the owner's paired devices (Sanctum tokens). */
@kotlinx.serialization.Serializable
data class DevicesResponse(val devices: List<DeviceDto> = emptyList())

/** One paired device row. `id` is the token id used to revoke/wipe it; `current` = this device. */
@kotlinx.serialization.Serializable
data class DeviceDto(
    // Sanctum token PK (numeric); used as the {token} path segment for revoke/wipe.
    val id: Long = 0,
    val current: Boolean = false,
    val name: String = "",
    val meta: String = "",
    val version: String? = null,
    val installId: String? = null,
    val syncing: Boolean = false,
    val wipeRequested: Boolean = false,
)
