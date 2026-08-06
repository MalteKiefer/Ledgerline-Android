package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Create a public share (file or folder). Byte-shape matches the web `_shareBody`
 * (`resources/js/components/files.js`). [kind] is `"file"|"folder"`; [expiresAt]/[password]
 * are omitted when null (kotlinx drops null-default properties). `allow_download` is
 * always sent (no default).
 */
@Serializable
data class ShareCreateRequest(
    val kind: String? = null,
    @SerialName("sealed_manifest") val sealedManifest: String,
    @SerialName("blob_refs") val blobRefs: List<String>,
    @SerialName("allow_download") val allowDownload: Boolean,
    @SerialName("expires_at") val expiresAt: String? = null,
    val password: String? = null,
)

/** Update an existing share (full manifest replace). Drops `kind`, adds `clear_password`. */
@Serializable
data class ShareUpdateRequest(
    @SerialName("sealed_manifest") val sealedManifest: String,
    @SerialName("blob_refs") val blobRefs: List<String>,
    @SerialName("allow_download") val allowDownload: Boolean,
    @SerialName("expires_at") val expiresAt: String? = null,
    val password: String? = null,
    @SerialName("clear_password") val clearPassword: Boolean? = null,
    // Optimistic concurrency: the version this update expects the server row to be at. Omitted for
    // shares created before the server carried a version (server then keeps the blind path).
    @SerialName("expected_version") val expectedVersion: Int? = null,
)

/** `{ "token": "<22-char>", "version": N }` — the created/updated share's public token + row version. */
@Serializable
data class ShareTokenResponse(val token: String, val version: Int? = null)
