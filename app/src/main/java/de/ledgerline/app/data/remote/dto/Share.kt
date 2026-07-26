package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Create a public share (files or gallery). Byte-shape matches the web `_shareBody`
 * (`resources/js/components/{files,gallery}.js`). [kind] is files-only (`"file"|"folder"`)
 * and omitted for gallery; [expiresAt]/[password] are omitted when null (kotlinx drops
 * null-default properties). `allow_download` is always sent (no default).
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
)

/** `{ "token": "<22-char>" }` — the created/updated share's public token. */
@Serializable
data class ShareTokenResponse(val token: String)
