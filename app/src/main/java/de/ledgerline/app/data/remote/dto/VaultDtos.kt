package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable data class VaultResponse(
    val configured: Boolean = false,
    val salt: String? = null,
    val kdf_ops: Long? = null,
    val kdf_mem: Long? = null,
    val wrapped_vault_key: String? = null,
    val wrap_nonce: String? = null,
    val has_recovery: Boolean = false,
    val wrapped_vault_key_recovery: String? = null,
    val recovery_nonce: String? = null,
)
