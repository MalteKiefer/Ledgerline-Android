package de.ledgerline.app.core.crypto

import de.ledgerline.app.data.remote.dto.PublishKeysRequest
import de.ledgerline.app.data.remote.dto.VaultKeysResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A user's cross-user **sharing identity**: an X25519 keypair + an ML-KEM-768
 * keypair (as a 64-byte seed). Public halves are published to `/vaults/keys`; the
 * secret halves (`x25519Sk`, `mlkemSeed`) are sealed under the Vault Key and held
 * only in memory.
 */
data class Identity(
    val x25519Pub: ByteArray,
    val x25519Sk: ByteArray,
    val mlkemEk: ByteArray,
    val mlkemSeed: ByteArray,
)

/**
 * Builds + parses the `/vaults/keys` identity material, byte-compatible with the web
 * (`vault.js`) and iOS (`IdentityKeyStore.swift`):
 *   - X25519 keypair (`PQKEM.x25519Keypair`), ML-KEM-768 keypair (`PQKEM.mlkemKeypair`)
 *   - secrets sealed under VK as `{"c","n"}` JSON strings (`Crypto.sealValue`)
 *   - fingerprint = hex(BLAKE2b-16(x25519 pub))
 */
@Singleton
class IdentityCrypto @Inject constructor(
    private val pqkem: PQKEM,
    private val crypto: Crypto,
) {
    /** A fresh identity (both keypairs). Secrets are in-memory only. */
    fun generate(): Identity {
        val (xPub, xSk) = pqkem.x25519Keypair()
        val ml = pqkem.mlkemKeypair()
        return Identity(x25519Pub = xPub, x25519Sk = xSk, mlkemEk = ml.ek, mlkemSeed = ml.seed)
    }

    /** TOFU fingerprint of an X25519 public key: hex(BLAKE2b-16(pub)). */
    fun fingerprintHex(x25519Pub: ByteArray): String =
        crypto.genericHash(x25519Pub, 16).joinToString("") { "%02x".format(it) }

    /** The PUT /vaults/keys body for [id], with secrets sealed under [vk]. */
    fun publishBody(id: Identity, vk: ByteArray) = PublishKeysRequest(
        public_key = crypto.b64encode(id.x25519Pub),
        wrapped_secret_key = crypto.sealValue(id.x25519Sk, vk),
        fingerprint = fingerprintHex(id.x25519Pub),
        mlkem_public_key = crypto.b64encode(id.mlkemEk),
        wrapped_mlkem_secret_key = crypto.sealValue(id.mlkemSeed, vk),
    )

    /**
     * Unwrap a fetched (already-published) identity back to secrets under [vk].
     * Fail-closed (null) if any field is missing, the fingerprint does not match the
     * public key, or a secret does not decrypt.
     */
    fun unwrap(resp: VaultKeysResponse, vk: ByteArray): Identity? {
        val pubB64 = resp.public_key ?: return null
        val wsk = resp.wrapped_secret_key ?: return null
        val ekB64 = resp.mlkem_public_key ?: return null
        val wseed = resp.wrapped_mlkem_secret_key ?: return null

        val pub = crypto.b64decode(pubB64)
        // Verify the server-reported fingerprint matches the public key (constant-time).
        resp.fingerprint?.let { fp ->
            if (!ConstantTime.equal(fingerprintHex(pub).toByteArray(), fp.toByteArray())) return null
        }
        val xSk = crypto.openValue(wsk, vk) ?: return null
        val seed = crypto.openValue(wseed, vk) ?: return null
        return Identity(x25519Pub = pub, x25519Sk = xSk, mlkemEk = crypto.b64decode(ekB64), mlkemSeed = seed)
    }
}
