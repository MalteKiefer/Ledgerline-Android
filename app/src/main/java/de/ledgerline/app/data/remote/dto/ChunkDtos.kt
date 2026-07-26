package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

/** `POST .../upload/init` — start an S3-multipart upload of an encrypted blob of [size] bytes. */
@Serializable data class UploadInitRequest(val size: Long)

/** `.../upload/init` response: the multipart [token], the eventual blob [id], and the server [partSize]. */
@Serializable data class UploadInitResponse(val token: String, val id: String, val partSize: Long)

/** `.../upload/part` response: the uploaded [part] number and its S3 [etag]. */
@Serializable data class UploadPartResponse(val part: Int, val etag: String)

/** One completed part in the finalize request. */
@Serializable data class PartRef(val part: Int, val etag: String)

/** `.../upload/complete` — finalize the multipart upload with the ordered [parts]. */
@Serializable data class UploadCompleteRequest(val token: String, val parts: List<PartRef>)

/** `.../upload/abort` — discard an in-progress multipart upload. */
@Serializable data class UploadAbortRequest(val token: String)
