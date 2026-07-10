package de.ledgerline.app.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Result of a CryptoObject-bound app-lock prompt. */
sealed interface CryptoAuth {
    /** The cipher, now authorised for exactly one keystore operation. */
    data class Success(val cipher: javax.crypto.Cipher) : CryptoAuth
    data object Failed : CryptoAuth
    data object Unavailable : CryptoAuth
}

/**
 * Application lock gate: biometric class 3 with device-credential fallback. The
 * prompt is bound to a keystore [BiometricPrompt.CryptoObject], so a single
 * successful auth authorizes exactly one use of the auth-gated AndroidKeystore key
 * that seals the session (see KeystoreSealer). This is the two-factor design: one
 * CryptoObject-bound biometric authorizes the keystore decrypt, and the passphrase
 * derives the Vault Key — no separate plain biometric.
 */
class AppLock {
    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canAuthenticate(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Prompt biometric/device-credential bound to [cryptoObject]. On success the
     * returned cipher is authorised for one keystore operation. DEVICE_CREDENTIAL
     * works with a CryptoObject on API 30+ (minSdk here). Resumes when resolved.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cryptoObject: BiometricPrompt.CryptoObject,
    ): CryptoAuth =
        suspendCancellableCoroutine { cont ->
            if (!canAuthenticate(activity)) {
                cont.resume(CryptoAuth.Unavailable)
                return@suspendCancellableCoroutine
            }
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val c = result.cryptoObject?.cipher
                        if (cont.isActive) cont.resume(if (c != null) CryptoAuth.Success(c) else CryptoAuth.Failed)
                    }

                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (cont.isActive) cont.resume(CryptoAuth.Failed)
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticators)
                .build()
            prompt.authenticate(info, cryptoObject)
        }
}
