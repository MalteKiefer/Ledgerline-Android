package de.ledgerline.app.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface LockResult {
    data object Success : LockResult
    data object Unavailable : LockResult
    data class Failed(val code: Int, val message: String) : LockResult
}

/**
 * Application lock gate: biometric class 3 with device-credential fallback. A
 * successful prompt authorizes use of the auth-gated AndroidKeystore key that
 * seals the session (see KeystoreSealer).
 */
class AppLock {
    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canAuthenticate(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    /** Prompt biometric/device-credential. Resumes when the user resolves it. */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String): LockResult =
        suspendCancellableCoroutine { cont ->
            if (!canAuthenticate(activity)) {
                cont.resume(LockResult.Unavailable)
                return@suspendCancellableCoroutine
            }
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(LockResult.Success)
                    }

                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (cont.isActive) cont.resume(LockResult.Failed(code, msg.toString()))
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticators)
                .build()
            prompt.authenticate(info)
        }
}
