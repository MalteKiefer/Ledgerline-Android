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
 * requireAuth=true the key can only be used after a successful BiometricPrompt and
 * is invalidated if the user enrolls a new biometric.
 *
 * Blob layout: [1-byte IV length][IV][GCM ciphertext+tag].
 */
class KeystoreSealer(
    private val alias: String = "ledgerline_token_key",
    private val requireAuth: Boolean = true,
) {
    private companion object {
        // Window (seconds) a single auth authorizes key use for.
        const val AUTH_VALIDITY_SECONDS = 30
    }

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
                    // Time-bound auth: a successful biometric/device-credential prompt
                    // authorizes key use for a short window, so the app can seal/unseal
                    // the session right after the app-lock prompt without binding a
                    // CryptoObject to each operation. A future hardening could switch to
                    // per-use auth (validity 0) with a CryptoObject-bound prompt.
                    setUserAuthenticationParameters(
                        AUTH_VALIDITY_SECONDS,
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

    fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        return ByteArray(1 + iv.size + ct.size).apply {
            this[0] = iv.size.toByte()
            System.arraycopy(iv, 0, this, 1, iv.size)
            System.arraycopy(ct, 0, this, 1 + iv.size, ct.size)
        }
    }

    fun open(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt() and 0xFF
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ct = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    fun clear() {
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }
}
