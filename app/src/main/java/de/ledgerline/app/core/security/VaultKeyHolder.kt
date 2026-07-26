package de.ledgerline.app.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Holds the Vault Key in memory only. Never persisted. Wiped on lock/idle/background. */
@Singleton
class VaultKeyHolder @Inject constructor() {
    @Volatile private var vk: ByteArray? = null
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    fun set(key: ByteArray) {
        vk?.fill(0)          // zero the previous key before releasing it to GC
        vk = key
        _unlocked.value = true
    }
    fun get(): ByteArray? = vk
    fun wipe() {
        vk?.fill(0)          // overwrite key bytes before releasing
        vk = null
        _unlocked.value = false
    }
}
