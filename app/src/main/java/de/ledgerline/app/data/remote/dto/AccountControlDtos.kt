package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

// ── Account control ──

/** `DELETE /account` — GDPR erasure; `confirmation` must equal the user's email. */
@Serializable data class DeleteAccountRequest(val confirmation: String)
@Serializable data class DeleteAccountResponse(val deleted: Boolean = false)

// ── Locale / theme ──

@Serializable data class LocaleRequest(val locale: String)
@Serializable data class ThemeRequest(val theme: String)   // light | dark | system

// ── Login (account) 2FA — orthogonal to the ZK vault passphrase ──

@Serializable data class TwoFactorQrResponse(val svg: String = "", val secret: String = "", val uri: String = "")
@Serializable data class TwoFactorConfirmRequest(val code: String)
/** Password step-up body — required (v1.562.0 max-security) on 2FA enable/recovery-codes/regenerate/disable. */
@Serializable data class CurrentPasswordRequest(val current_password: String)
@Serializable data class RecoveryCodesResponse(val recovery_codes: List<String> = emptyList())
@Serializable data class TwoFactorEnabledResponse(val enabled: Boolean = false)

/** `PUT /user/password` — change the app LOGIN password (not the vault passphrase). */
@Serializable data class ChangePasswordRequest(
    val current_password: String,
    val password: String,
    val password_confirmation: String,
)
