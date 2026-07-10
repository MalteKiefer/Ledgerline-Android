package de.ledgerline.app.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets (bearer token, base URL, SPKI pin) with a hardware-backed
 * AES-256-GCM key in the AndroidKeystore. StrongBox is requested on real hardware
 * and transparently skipped where unavailable (e.g. the emulator). When
 * requireAuth=true the key requires per-use authentication: every seal/unseal
 * operation must be authorized by a CryptoObject-bound BiometricPrompt (validity 0),
 * and the key is invalidated if the user enrolls a new biometric.
 *
 * Per-use auth means callers cannot use the inline [seal]/[open] helpers with an
 * auth-gated key — the cipher init only succeeds after the CryptoObject-bound
 * prompt. Instead they build a cipher via [encryptCipher]/[decryptCipher], run the
 * biometric prompt on it (see AppLock), then finish with [finishSeal]/[finishOpen].
 * This guarantees exactly ONE biometric prompt per session read/write.
 *
 * The alias was bumped to `_v2` when the auth params changed from time-bound to
 * per-use: an existing key created with the old params cannot be reused with the
 * new ones, so a fresh key is generated under the new alias. Any session sealed
 * with the old key becomes undecryptable → the user re-pairs once (documented).
 *
 * Blob layout: [1-byte IV length][IV][GCM ciphertext+tag].
 */
class KeystoreSealer(
    private val alias: String = "ledgerline_token_key_v2",
    private val requireAuth: Boolean = true,
) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun buildSpec(strongBox: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (strongBox) setIsStrongBoxBacked(true)
                if (requireAuth) {
                    setUserAuthenticationRequired(true)
                    // Per-use auth (validity 0): every cipher operation must be
                    // authorized by a CryptoObject-bound BiometricPrompt. This is the
                    // correct two-factor design — exactly one biometric authorizes the
                    // keystore decrypt of the sealed session, and the passphrase derives
                    // the Vault Key. DEVICE_CREDENTIAL works with a CryptoObject on API 30+.
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                    setInvalidatedByBiometricEnrollment(true)
                }
            }
            .build()

    private fun getOrCreateKey(): SecretKey {
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            gen.init(buildSpec(strongBox = true))
            gen.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            // No StrongBox (e.g. emulator) — fall back to TEE-backed key.
            gen.init(buildSpec(strongBox = false))
            gen.generateKey()
        }
    }

    // --- Per-use (CryptoObject-bound) path: the caller runs the biometric on the
    // cipher between the *Cipher and finish* calls (see AppLock / SessionStore). ---

    /** Cipher initialised for encryption; wrap in a CryptoObject and prompt, then [finishSeal]. */
    fun encryptCipher(): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return c
    }

    /** Complete a seal with the authorised [cipher] from [encryptCipher]. */
    fun finishSeal(cipher: Cipher, plaintext: ByteArray): ByteArray {
        val ct = cipher.doFinal(plaintext)
        val iv = cipher.iv
        return ByteArray(1 + iv.size + ct.size).apply {
            this[0] = iv.size.toByte()
            System.arraycopy(iv, 0, this, 1, iv.size)
            System.arraycopy(ct, 0, this, 1 + iv.size, ct.size)
        }
    }

    /** Cipher initialised for decryption of [blob]; wrap in a CryptoObject and prompt, then [finishOpen]. */
    fun decryptCipher(blob: ByteArray): Cipher {
        val ivLen = blob[0].toInt() and 0xFF
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return c
    }

    /** Complete an open with the authorised [cipher] from [decryptCipher]. */
    fun finishOpen(cipher: Cipher, blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt() and 0xFF
        return cipher.doFinal(blob.copyOfRange(1 + ivLen, blob.size))
    }

    // --- Inline path: only valid with requireAuth=false (no per-use auth needed).
    // Used by KeystoreSealerTest. ---

    fun seal(plaintext: ByteArray): ByteArray = finishSeal(encryptCipher(), plaintext)

    fun open(blob: ByteArray): ByteArray = finishOpen(decryptCipher(blob), blob)

    fun clear() {
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }
}
