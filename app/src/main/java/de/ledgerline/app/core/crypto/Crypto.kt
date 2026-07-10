package de.ledgerline.app.core.crypto

/**
 * Zero-knowledge primitives, byte-compatible with resources/js/vault.js.
 * Base64 is libsodium ORIGINAL variant (standard, padded).
 * Phase 1 uses deriveKek + secretBoxOpen + genericHash32 only; the rest are
 * defined for later phases.
 */
interface Crypto {
    /** Argon2id (ALG_ARGON2ID13): passphrase + 16-byte salt -> 32-byte KEK. */
    fun deriveKek(passphrase: ByteArray, salt: ByteArray, opsLimit: Long, memLimit: Long): ByteArray

    /** crypto_secretbox_open_easy. Returns null on auth failure (wrong key). */
    fun secretBoxOpen(cipher: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray?

    /** crypto_generichash to 32 bytes, keyless (recovery key derivation). */
    fun genericHash32(input: ByteArray): ByteArray

    fun b64decode(s: String): ByteArray
    fun b64encode(b: ByteArray): String
    fun fromHex(s: String): ByteArray
}
