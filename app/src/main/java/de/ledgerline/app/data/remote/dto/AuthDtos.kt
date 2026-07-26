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
