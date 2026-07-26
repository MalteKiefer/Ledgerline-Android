package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class UploadResponse(val id: String)
/**
 * Sealed-store PUT body. [shards] is the referential-integrity guard for the SHARDED
 * stores (gallery/files v3): the union of all shard + collection blob refs the new root
 * points at. Null (default) for the module stores → omitted from the JSON, so their PUTs
 * are unchanged.
 */
@Serializable data class StorePutRequest(
    val ciphertext: String,
    val version: Int,
    val shards: List<String>? = null,
)
@Serializable data class UsageResponse(val used: Long = 0, val quota: Long = 0)

/** `POST .../blobs/reconcile` request: every blob id still referenced by the manifest. */
@Serializable data class ReconcileRequest(val blobs: List<String>)

/** `POST .../blobs/reconcile` response: the resulting usage after the server frees orphans. */
@Serializable data class ReconcileResponse(val used: Long = 0, val quota: Long = 0)
