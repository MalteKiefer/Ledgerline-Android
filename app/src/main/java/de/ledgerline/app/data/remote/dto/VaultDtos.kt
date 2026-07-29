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

/** `PUT /vaults/{vault}/store` body — re-seal under VK_vault with optimistic concurrency. */
@Serializable data class SharedVaultStorePut(
    @SerialName("sealed_manifest") val sealedManifest: String,
    val version: Int,
)

// ── Owner-side shared-vault provisioning/management ──

/** `POST /vaults` — VK_vault wrapped to the owner's OWN identity (PQ-hybrid envelope JSON). */
@Serializable data class CreateVaultRequest(
    @SerialName("wrapped_vault_key") val wrappedVaultKey: String,
    val kind: String = "folder",
)

/** `{ id }` — the new vault's UUID (server IdResponse). */
@Serializable data class VaultCreatedResponse(val id: String = "")

/** `POST /vaults/{vault}/resolve-recipient` — enum-resistant lookup by email/handle. */
@Serializable data class ResolveRecipientRequest(val identifier: String)

@Serializable data class ResolvedRecipientDto(
    @SerialName("user_id") val userId: Long = 0,
    @SerialName("public_key") val publicKey: String? = null,
    val fingerprint: String? = null,
    @SerialName("mlkem_public_key") val mlkemPublicKey: String? = null,
)

/** `POST /vaults/{vault}/members` — invite: VK_vault hybrid-wrapped to the recipient's identity. */
@Serializable data class AddMemberRequest(
    @SerialName("user_id") val userId: Long,
    val role: String,
    @SerialName("wrapped_vault_key") val wrappedVaultKey: String,
    @SerialName("recipient_fingerprint") val recipientFingerprint: String? = null,
)

@Serializable data class UpdateMemberRequest(val role: String)

/** `GET /vaults/{vault}/members` row. */
@Serializable data class VaultMemberDto(
    val id: Long = 0,
    @SerialName("user_id") val userId: Long = 0,
    val name: String? = null,
    val email: String? = null,
    val role: String = "viewer",
    val status: String = "pending",
    @SerialName("public_key") val publicKey: String? = null,
    @SerialName("mlkem_public_key") val mlkemPublicKey: String? = null,
    @SerialName("recipient_fingerprint") val recipientFingerprint: String? = null,
)

/** `POST /vaults/{vault}/rotate` — atomic remove-member + re-key + re-seal. */
@Serializable data class RotateRequest(
    @SerialName("sealed_manifest") val sealedManifest: String,
    @SerialName("expected_version") val expectedVersion: Int,
    @SerialName("remove_member_id") val removeMemberId: Long,
    val members: List<RotateMember>,
)

@Serializable data class RotateMember(
    @SerialName("user_id") val userId: Long,
    @SerialName("wrapped_vault_key") val wrappedVaultKey: String,
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
