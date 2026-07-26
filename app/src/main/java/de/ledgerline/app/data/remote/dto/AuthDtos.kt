package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class PairClaimRequest(val code: String, val device_name: String)
@Serializable data class PairClaimResponse(val status: String)

/** `POST /api/v1/auth/pair/collect` — poll for approval with the same one-time code. */
@Serializable data class PairCollectRequest(val code: String)

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
