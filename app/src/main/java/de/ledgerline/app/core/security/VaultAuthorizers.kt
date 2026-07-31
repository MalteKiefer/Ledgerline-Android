package de.ledgerline.app.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * Builds the CryptoObject-bound biometric authorizer threaded into the pairing + app-lock screens,
 * so `MainActivity` doesn't hand-roll the lambda. [authorize] runs a STRONG biometric OR device
 * credential to authorize the Keystore session-blob key, returning the authorized cipher (or null on
 * cancel/failure). No vault key exists in the plaintext-relational pivot — one prompt reveals the
 * session token. [strongAuthorize] is kept STRONG-only for callers that still thread it.
 */
class VaultAuthorizers(
    private val activity: FragmentActivity,
    private val lockTitle: String,
    private val lockSubtitle: String,
    private val cancelText: String,
    private val appLock: AppLock = AppLock(),
    private val strongAppLock: AppLock = AppLock(BiometricManager.Authenticators.BIOMETRIC_STRONG),
) {
    val authorize: suspend (Cipher) -> Cipher? = { cipher ->
        (appLock.authenticate(activity, lockTitle, lockSubtitle, BiometricPrompt.CryptoObject(cipher))
            as? CryptoAuth.Success)?.cipher
    }

    val strongAuthorize: suspend (Cipher) -> Cipher? = { cipher ->
        (strongAppLock.authenticate(
            activity, lockTitle, lockSubtitle, BiometricPrompt.CryptoObject(cipher),
            negativeButtonText = cancelText,
        ) as? CryptoAuth.Success)?.cipher
    }
}
