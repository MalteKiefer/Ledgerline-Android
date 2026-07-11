package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class UploadResponse(val id: String)
@Serializable data class StorePutRequest(val ciphertext: String, val version: Int)
@Serializable data class UsageResponse(val used: Long = 0, val quota: Long = 0)

// --- Chunked / S3-multipart upload (large files) ------------------------------
@Serializable data class UploadInitRequest(val size: Long)
@Serializable data class UploadInitResponse(val token: String, val id: String, val partSize: Long)
@Serializable data class PartRef(val part: Int, val etag: String)
@Serializable data class UploadPartResponse(val part: Int, val etag: String)
@Serializable data class UploadCompleteRequest(val token: String, val parts: List<PartRef>)
@Serializable data class UploadCompleteResponse(val id: String)
@Serializable data class UploadAbortRequest(val token: String)

// --- Blob reconcile (quota self-heal) -----------------------------------------
// The server frees its own blobs NOT in [blobs] (older than the grace window) and
// returns the resulting usage. [blobs] = every blob id the manifest still references.
@Serializable data class ReconcileRequest(val blobs: List<String>)
@Serializable data class ReconcileResponse(val used: Long = 0, val quota: Long = 0)
