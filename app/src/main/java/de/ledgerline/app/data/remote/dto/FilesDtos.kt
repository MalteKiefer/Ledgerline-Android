package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class UploadResponse(val id: String)
@Serializable data class StorePutRequest(val ciphertext: String, val version: Int)
@Serializable data class UsageResponse(val used: Long = 0, val quota: Long = 0)
