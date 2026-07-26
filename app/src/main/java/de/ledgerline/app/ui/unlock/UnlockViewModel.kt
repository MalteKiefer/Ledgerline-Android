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
    /** Too many failures: an attempt is blocked for [seconds] more (never destructive). */
    data class LockedOut(val seconds: Int) : UnlockUiState
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
    private val throttle: de.ledgerline.app.core.security.UnlockThrottle,
    private val duressGuard: de.ledgerline.app.core.security.DuressGuard,
    private val securityLog: de.ledgerline.app.core.security.SecurityLog,
    private val authEventBus: de.ledgerline.app.core.AuthEventBus,
    private val clockGuard: de.ledgerline.app.core.security.ClockRollbackGuard,
    private val identityRepository: de.ledgerline.app.data.IdentityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<UnlockUiState>(UnlockUiState.Idle)
    val state: StateFlow<UnlockUiState> = _state

    /** True when a non-expired remembered blob exists and STRONG biometrics are enrolled —
     *  the unlock screen then offers a passphrase-free biometric unlock. */
    private val _canQuickUnlock = MutableStateFlow(false)
    val canQuickUnlock: StateFlow<Boolean> = _canQuickUnlock

    init { viewModelScope.launch { refreshQuickUnlock() } }

    private suspend fun refreshQuickUnlock() {
        val now = System.currentTimeMillis()
        // A wall-clock rollback must not honour the remembered TTL (clock-rollback guard).
        val rolledBack = clockGuard.observe(now)
        _canQuickUnlock.value =
            !rolledBack && biometric.strongEnrolled() && rememberedVault.hasValid(now)
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
            val now = System.currentTimeMillis()
            // Clock-rollback guard: refuse the passphrase-free path if the wall clock
            // moved backwards (an attacker extending the remembered-vault TTL). Fall back
            // to the passphrase UI.
            if (clockGuard.observe(now)) {
                refreshQuickUnlock()
                _state.value = UnlockUiState.Idle
                return@launch
            }
            val opened = rememberedVault.open(now, authorize)
            if (opened == null) {
                refreshQuickUnlock() // a dead blob may have been cleared
                _state.value = UnlockUiState.Idle
                return@launch
            }
            sessionHolder.set(opened.session)
            holder.set(opened.vk)
            onUnlockSuccess() // biometric quick-unlock is a legitimate entry
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
            // Escalating-backoff gate: block (never wipe) while a lockout is active.
            val lockMs = throttle.remainingLockMs()
            if (lockMs > 0) {
                passphrase.fill(' ')
                _state.value = UnlockUiState.LockedOut(((lockMs + 999) / 1000).toInt())
                return@launch
            }
            _state.value = UnlockUiState.Working
            val session = sessionStore.load(authorize)
                ?: run { _state.value = UnlockUiState.Error("no session or auth cancelled"); return@launch }
            sessionHolder.set(session)
            val bytes = charsToUtf8(passphrase)
            val result = withContext(Dispatchers.Default) { // Argon2id is CPU-heavy
                UnlockVault(crypto, holder).withPassphrase(VaultRepository(session), bytes)
            }
            passphrase.fill(' ')
            when (result) {
                is Outcome.Ok -> {
                    onUnlockSuccess()
                    maybeArmRemembered(strongAuthorize) // seal/refresh the remembered VK
                    _state.value = UnlockUiState.Unlocked
                }
                is Outcome.Err -> _state.value = when (result.kind) {
                    // Only a genuine wrong passphrase drives the throttle + duress counters.
                    ErrorKind.WRONG_PASSPHRASE -> onWrongPassphrase()
                    ErrorKind.NOT_CONFIGURED -> UnlockUiState.NotConfigured
                    else -> UnlockUiState.Error(result.kind.name)
                }
            }
        }
    }

    /** A successful entry clears both the escalating lockout and the duress counter. */
    private suspend fun onUnlockSuccess() {
        throttle.recordSuccess()
        duressGuard.reset()
        securityLog.record(de.ledgerline.app.core.security.SecurityEventType.UNLOCK_SUCCESS)
        // Best-effort: ensure the sharing identity is published (fire-and-forget so it
        // never delays entering the app). Needs the VK, which is set by now.
        viewModelScope.launch { runCatching { identityRepository.ensure() } }
    }

    /**
     * Handle a genuine wrong-passphrase attempt: advance the escalating lockout and the
     * persisted duress counter, log the failure, and — at the threshold — fire the local
     * wipe (reusing the remote-wipe path) leaving an empty, unpaired app. Returns the UI
     * state to show (a lockout countdown or a plain error).
     */
    private suspend fun onWrongPassphrase(): UnlockUiState {
        throttle.recordFailure()
        val failures = withContext(Dispatchers.Default) { duressGuard.increment() }
        securityLog.record(de.ledgerline.app.core.security.SecurityEventType.UNLOCK_FAILED)

        val threshold = settingsStore.duressThreshold.first()
        if (de.ledgerline.app.core.security.WipePolicy.shouldWipe(failures, threshold)) {
            securityLog.record(de.ledgerline.app.core.security.SecurityEventType.DURESS_WIPE)
            authEventBus.emitWipe() // → AppNav collector: ForceLogout (wipes all local) + re-pair
            return UnlockUiState.Working // the nav will route away to pairing
        }

        val lockMs = throttle.remainingLockMs()
        if (lockMs > 0) {
            securityLog.record(de.ledgerline.app.core.security.SecurityEventType.THROTTLE_LOCKOUT)
            return UnlockUiState.LockedOut(((lockMs + 999) / 1000).toInt())
        }
        return UnlockUiState.Error("wrong")
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
                    // A valid recovery code is a legitimate entry: clear the lockout + duress
                    // counter (recovery FAILURES, by contrast, never count toward either).
                    throttle.recordSuccess()
                    duressGuard.reset()
                    securityLog.record(de.ledgerline.app.core.security.SecurityEventType.RECOVERY_UNLOCK)
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
