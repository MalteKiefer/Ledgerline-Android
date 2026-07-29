package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One row of `GET /vaults` — a cross-user shared-vault membership, incl. the VK_vault wrapped to me. */
@Serializable data class VaultMembershipDto(
    val id: Long = 0,
    @SerialName("vault_id") val vaultId: String = "",
    val role: String = "viewer",                     // viewer | editor | manager
    val status: String = "pending",                  // pending | active | revoked
    val kind: String = "folder",                     // password | folder
    val owner: Boolean = false,
    @SerialName("wrapped_vault_key") val wrappedVaultKey: String? = null,
)

/** `GET /vaults/{vault}/store` — the shared vault's sealed manifest (opaque; sealed under VK_vault). */
@Serializable data class SharedVaultStoreResponse(
    @SerialName("sealed_manifest") val sealedManifest: String? = null,
    val version: Int = 0,
)

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

/** GET /vaults/keys — the caller's published sharing identity (all null before first publish). */
@Serializable data class VaultKeysResponse(
    val public_key: String? = null,               // X25519 public, base64 (32 B)
    val wrapped_secret_key: String? = null,       // {"c","n"} JSON — X25519 sk sealed under VK
    val fingerprint: String? = null,              // hex(BLAKE2b-16(public_key bytes))
    val mlkem_public_key: String? = null,         // ML-KEM-768 ek, base64 (1184 B)
    val wrapped_mlkem_secret_key: String? = null, // {"c","n"} JSON — 64 B seed sealed under VK
)

/** PUT /vaults/keys — publish identity (write-once; 409 key_conflict if public_key changes). */
@Serializable data class PublishKeysRequest(
    val public_key: String,
    val wrapped_secret_key: String,
    val fingerprint: String,
    val mlkem_public_key: String,
    val wrapped_mlkem_secret_key: String,
)
