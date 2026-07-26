package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

/** `GET /passwords/icon?domain=` → a favicon/BIMI data URI, or null. */
@Serializable data class IconResponse(val icon: String? = null)

/** `GET /passwords/tfa-directory` → domain → 2FA-docs URL map. */
@Serializable data class TfaDirectoryResponse(val entries: Map<String, String> = emptyMap())
