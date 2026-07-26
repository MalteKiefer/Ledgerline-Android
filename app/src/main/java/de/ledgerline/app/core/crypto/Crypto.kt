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

    /**
     * Decrypt a sealed manifest string `{"c":...,"n":...}` with the vault key.
     * Returns the plaintext JSON (trailing 4-KiB whitespace padding intact; the
     * JSON parser ignores it), or null if decryption fails.
     */
    fun openManifest(ciphertext: String, vk: ByteArray): String?

    /** Inverse of openManifest: pad to a 4-KiB bucket and secretbox-seal to `{"c","n"}`. */
    fun sealManifest(json: String, vk: ByteArray): String

    // The following back the sharing-identity crypto (§ PQKEM / IdentityCrypto). They
    // have default throwing bodies so the many lightweight test fakes that never touch
    // them need no changes; the real [SodiumCrypto] overrides all three.

    /** secretbox-seal raw [data] under [key] → JSON `{"c","n"}` (random nonce, NO padding). */
    fun sealValue(data: ByteArray, key: ByteArray): String = throw NotImplementedError()

    /** Open a `{"c","n"}` JSON string under [key]; null on failure. */
    fun openValue(cn: String, key: ByteArray): ByteArray? = throw NotImplementedError()

    /** crypto_generichash (BLAKE2b) to [outLen] bytes, keyless. */
    fun genericHash(input: ByteArray, outLen: Int): ByteArray = throw NotImplementedError()

    /**
     * Constant-time equality for secret comparisons (TOFU fingerprints, MACs), routed
     * through libsodium `sodium_memcmp` in [SodiumCrypto] (vetted primitive). Unequal
     * lengths return false without probing the bytes. The default falls back to the
     * pure-Kotlin [ConstantTime.equal] so lightweight test fakes need no override.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = ConstantTime.equal(a, b)

    /** Plaintext slice size a caller must feed the streaming content cipher (4 MiB). */
    val contentChunkSize: Int

    /** Streaming content encryptor with a fresh per-file key (secretstream). */
    fun newContentEncryptor(vk: ByteArray): ContentEncryptor

    /** Streaming content decryptor; unwraps the per-file key with vk. */
    fun contentDecryptor(encFileKey: String, vk: ByteArray): ContentDecryptor

    /** Little-endian u32 helpers for the frame length prefix. */
    fun u32le(n: Int): ByteArray
    fun readU32le(bytes: ByteArray, off: Int): Int

    interface ContentEncryptor {
        /** Secretstream header, 24 bytes, written first to the blob. */
        val header: ByteArray

        /** Encrypt one plaintext chunk → framed `u32le(cipherLen) ++ cipher`. */
        fun encryptChunk(chunk: ByteArray, isLast: Boolean): ByteArray

        /** The wrapped per-file key as a JSON `{"c","n"}` string. */
        fun sealKey(): String
    }

    interface ContentDecryptor {
        /** Secretstream header size (24 bytes). */
        val headerBytes: Int

        /** Start the pull side from the blob's leading header bytes. */
        fun start(header: ByteArray)

        /** Decrypt one raw ciphertext frame → (message, isFinal). */
        fun decryptFrame(frame: ByteArray): Pair<ByteArray, Boolean>
    }
}
