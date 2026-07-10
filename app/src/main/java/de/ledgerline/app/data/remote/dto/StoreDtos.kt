package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class StoreResponse(val ciphertext: String? = null, val version: Int = 0)
