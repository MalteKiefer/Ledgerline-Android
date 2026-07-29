package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

/** `GET /s/{token}/meta` (no auth) — enough to decide the flow before touching the sealed manifest. */
@Serializable
data class ShareMetaResponse(
    val found: Boolean = false,
    val expired: Boolean = false,
    val kind: String = "",              // file | folder | gallery
    val needs_password: Boolean = false,
    val allow_download: Boolean = false,
    val name: String? = null,
)

/** `POST /s/{token}/unlock` — exchange the password for a short-lived HMAC grant. */
@Serializable
data class ShareUnlockRequest(val password: String)

@Serializable
data class ShareUnlockResponse(val ok: Boolean = false, val grant: String? = null)

/** `GET /s/{token}/manifest` — the sealed (share-key-encrypted) manifest ciphertext + download flag. */
@Serializable
data class ShareManifestResponse(val sealed: String = "", val allow_download: Boolean = false)
