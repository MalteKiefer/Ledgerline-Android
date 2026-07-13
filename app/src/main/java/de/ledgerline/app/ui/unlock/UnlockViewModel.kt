package de.ledgerline.app.ui.unlock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.BiometricAvailability
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.RememberedVaultStore
import de.ledgerline.app.data.SessionStore
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.VaultRepository
import de.ledgerline.app.domain.usecase.UnlockVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.CharBuffer
import javax.crypto.Cipher
import javax.inject.Inject

sealed interface UnlockUiState {
    data object Idle : UnlockUiState
    data object Working : UnlockUiState
    data object Unlocked : UnlockUiState
    data object NotConfigured : UnlockUiState
    data class Error(val message: String) : UnlockUiState
}

@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val crypto: Crypto,
    private val holder: VaultKeyHolder,
    private val sessionStore: SessionStore,
    private val sessionHolder: SessionHolder,
    private val settingsStore: SettingsStore,
    private val rememberedVault: RememberedVaultStore,
    private val biometric: BiometricAvailability,
) : ViewModel() {
    private val _state = MutableStateFlow<UnlockUiState>(UnlockUiState.Idle)
    val state: StateFlow<UnlockUiState> = _state

    /** True when a non-expired remembered blob exists and STRONG biometrics are enrolled —
     *  the unlock screen then offers a passphrase-free biometric unlock. */
    private val _canQuickUnlock = MutableStateFlow(false)
    val canQuickUnlock: StateFlow<Boolean> = _canQuickUnlock

    init { viewModelScope.launch { refreshQuickUnlock() } }

    private suspend fun refreshQuickUnlock() {
        _canQuickUnlock.value =
            biometric.strongEnrolled() && rememberedVault.hasValid(System.currentTimeMillis())
    }

    private val dayMillis = 86_400_000L

    /**
     * Unlock the vault from the biometric-sealed remembered blob — no passphrase. One
     * STRONG biometric via [authorize] opens the session + VK together. On cancel/expiry/
     * invalidation it silently returns to [UnlockUiState.Idle] so the passphrase UI stays.
     */
    fun quickUnlock(authorize: suspend (Cipher) -> Cipher?) {
        viewModelScope.launch {
            _state.value = UnlockUiState.Working
            val opened = rememberedVault.open(System.currentTimeMillis(), authorize)
            if (opened == null) {
                refreshQuickUnlock() // a dead blob may have been cleared
                _state.value = UnlockUiState.Idle
                return@launch
            }
            sessionHolder.set(opened.session)
            holder.set(opened.vk)
            _state.value = UnlockUiState.Unlocked
        }
    }

    /**
     * After a passphrase/recovery unlock, (re)arm the remembered vault if enabled: bump
     * the TTL when a valid blob already exists (no prompt), otherwise seal a fresh blob
     * (one STRONG biometric via [strongAuthorize]). Best-effort — cancelling never blocks
     * entry into the app.
     */
    private suspend fun maybeArmRemembered(strongAuthorize: suspend (Cipher) -> Cipher?) {
        if (!settingsStore.rememberVaultEnabled.first() || !biometric.strongEnrolled()) return
        val session = sessionHolder.get() ?: return
        val vk = holder.get() ?: return
        val now = System.currentTimeMillis()
        val expiry = now + settingsStore.rememberVaultDays.first() * dayMillis
        if (rememberedVault.hasValid(now)) {
            rememberedVault.bumpExpiry(expiry)
        } else {
            runCatching { rememberedVault.save(session, vk, expiry, strongAuthorize) }
        }
    }

    /**
     * Clear a stale error/not-configured message. Called when the unlock screen is
     * (re-)entered and while the user edits the passphrase, so a message left over
     * from a previous attempt (or a forced logout) does not persist. Never clobbers
     * an in-flight [UnlockUiState.Working] or a terminal [UnlockUiState.Unlocked].
     */
    fun reset() {
        if (_state.value is UnlockUiState.Error || _state.value is UnlockUiState.NotConfigured) {
            _state.value = UnlockUiState.Idle
        }
    }

    /**
     * Called when the unlock screen (re-)enters composition. Clears a stale message and
     * re-evaluates whether a biometric quick-unlock is available — the remembered blob may
     * have been armed since this (retained) ViewModel was created.
     */
    fun onShown() {
        reset()
        viewModelScope.launch { refreshQuickUnlock() }
    }

    /**
     * Unlock. [authorize] runs the single CryptoObject-bound biometric that
     * authorizes reading the sealed session; the passphrase then derives the VK.
     */
    fun unlock(
        passphrase: CharArray,
        authorize: suspend (Cipher) -> Cipher?,
        strongAuthorize: suspend (Cipher) -> Cipher?,
    ) {
        viewModelScope.launch {
            _state.value = UnlockUiState.Working
            val session = sessionStore.load(authorize)
                ?: run { _state.value = UnlockUiState.Error("no session or auth cancelled"); return@launch }
            sessionHolder.set(session)
            val bytes = charsToUtf8(passphrase)
            val result = withContext(Dispatchers.Default) { // Argon2id is CPU-heavy
                UnlockVault(crypto, holder).withPassphrase(VaultRepository(session), bytes)
            }
            _state.value = when (result) {
                is Outcome.Ok -> {
                    maybeArmRemembered(strongAuthorize) // seal/refresh the remembered VK
                    UnlockUiState.Unlocked
                }
                is Outcome.Err -> when (result.kind) {
                    ErrorKind.WRONG_PASSPHRASE -> UnlockUiState.Error("wrong")
                    ErrorKind.NOT_CONFIGURED -> UnlockUiState.NotConfigured
                    else -> UnlockUiState.Error(result.kind.name)
                }
            }
            passphrase.fill(' ')
        }
    }

    /**
     * Unlock via recovery code. Mirrors [unlock] (session load + one biometric via
     * [authorize]), but derives the VK from the recovery code instead of the passphrase.
     * The code is entered as hex in 4-char groups with spaces; whitespace is stripped
     * before it is passed to the crypto layer (web: `from_hex(code without spaces)`).
     */
    fun unlockWithRecovery(
        code: String,
        authorize: suspend (Cipher) -> Cipher?,
        strongAuthorize: suspend (Cipher) -> Cipher?,
    ) {
        viewModelScope.launch {
            _state.value = UnlockUiState.Working
            val session = sessionStore.load(authorize)
                ?: run { _state.value = UnlockUiState.Error("no session or auth cancelled"); return@launch }
            sessionHolder.set(session)
            val hex = code.filterNot { it.isWhitespace() }
            val result = withContext(Dispatchers.Default) {
                UnlockVault(crypto, holder).withRecoveryCode(VaultRepository(session), hex)
            }
            _state.value = when (result) {
                is Outcome.Ok -> {
                    maybeArmRemembered(strongAuthorize)
                    UnlockUiState.Unlocked
                }
                is Outcome.Err -> when (result.kind) {
                    ErrorKind.WRONG_PASSPHRASE -> UnlockUiState.Error("wrong")
                    ErrorKind.NOT_CONFIGURED -> UnlockUiState.NotConfigured
                    else -> UnlockUiState.Error(result.kind.name)
                }
            }
        }
    }

    private fun charsToUtf8(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
        return ByteArray(bb.remaining()).also { bb.get(it) }
    }
}
