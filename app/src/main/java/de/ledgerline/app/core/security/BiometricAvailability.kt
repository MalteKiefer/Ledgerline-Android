package de.ledgerline.app.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports whether a Class-3 (STRONG) biometric is enrolled and usable. Gates the
 * "remember vault unlock" feature: persisting the Vault Key behind biometrics is only
 * offered when real biometrics exist — a device PIN alone must never unlock it.
 */
@Singleton
class BiometricAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun strongEnrolled(): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
}
