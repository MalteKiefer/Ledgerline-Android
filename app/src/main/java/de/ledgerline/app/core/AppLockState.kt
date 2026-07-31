package de.ledgerline.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory app-lock gate for the plaintext-relational pivot (the zero-knowledge vault/VK is gone).
 * A biometric/device-credential prompt reads the Keystore-sealed session token into [SessionHolder]
 * and flips [unlocked] true; backgrounding/idle/logout locks it again. This replaces the old
 * VaultKeyHolder-driven unlock — there is no passphrase and no decryption key to hold.
 */
@Singleton
class AppLockState @Inject constructor() {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked
    fun unlock() { _unlocked.value = true }
    fun lock() { _unlocked.value = false }
}
