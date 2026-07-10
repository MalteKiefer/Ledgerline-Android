package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class PairClaimRequest(val code: String, val device_name: String)
@Serializable data class PairClaimResponse(val status: String)

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
