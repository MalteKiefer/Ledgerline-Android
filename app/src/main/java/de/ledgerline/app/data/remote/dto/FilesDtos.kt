package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class UploadResponse(val id: String)
@Serializable data class StorePutRequest(val ciphertext: String, val version: Int)
@Serializable data class UsageResponse(val used: Long = 0, val quota: Long = 0)

/** `POST .../blobs/reconcile` request: every blob id still referenced by the manifest. */
@Serializable data class ReconcileRequest(val blobs: List<String>)

/** `POST .../blobs/reconcile` response: the resulting usage after the server frees orphans. */
@Serializable data class ReconcileResponse(val used: Long = 0, val quota: Long = 0)
