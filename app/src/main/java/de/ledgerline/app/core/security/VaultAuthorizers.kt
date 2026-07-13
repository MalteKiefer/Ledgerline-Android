package de.ledgerline.app.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * Builds the two CryptoObject-bound biometric authorizers threaded into the unlock/pairing
 * screens, so `MainActivity` and `ShareActivity` don't each hand-roll the identical lambdas:
 *  - [authorize]: STRONG biometric OR device-credential — authorizes the session-blob key.
 *  - [strongAuthorize]: STRONG-biometric-only (with a negative button) — authorizes the
 *    remembered-vault key, which must never be unwrapped by a device PIN alone.
 * Each touches the idle timer before prompting. Returns the authorized cipher, or null on
 * cancel/failure.
 */
class VaultAuthorizers(
    private val activity: FragmentActivity,
    private val idleLocker: IdleLocker,
    private val lockTitle: String,
    private val lockSubtitle: String,
    private val rememberSubtitle: String,
    private val cancelText: String,
    private val appLock: AppLock = AppLock(),
    private val strongAppLock: AppLock = AppLock(BiometricManager.Authenticators.BIOMETRIC_STRONG),
) {
    val authorize: suspend (Cipher) -> Cipher? = { cipher ->
        idleLocker.touch()
        (appLock.authenticate(activity, lockTitle, lockSubtitle, BiometricPrompt.CryptoObject(cipher))
            as? CryptoAuth.Success)?.cipher
    }

    val strongAuthorize: suspend (Cipher) -> Cipher? = { cipher ->
        idleLocker.touch()
        (strongAppLock.authenticate(
            activity, lockTitle, rememberSubtitle, BiometricPrompt.CryptoObject(cipher),
            negativeButtonText = cancelText,
        ) as? CryptoAuth.Success)?.cipher
    }
}
