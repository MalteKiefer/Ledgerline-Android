package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class StoreResponse(val ciphertext: String? = null, val version: Int = 0)

/** One retained sealed-root version (recovery net). No ciphertext in the list. */
@Serializable data class StoreHistoryEntry(val version: Int = 0, val bytes: Long = 0, val created_at: String? = null)

/** `GET …/store/history` — retained versions, newest first. */
@Serializable data class StoreHistoryResponse(val versions: List<StoreHistoryEntry> = emptyList())

/** `GET …/store/history/{version}` — one retained sealed root ciphertext. */
@Serializable data class StoreHistoryVersion(val version: Int = 0, val ciphertext: String = "")
