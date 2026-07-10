package de.ledgerline.app.ui.unlock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.SessionStore
import de.ledgerline.app.data.VaultRepository
import de.ledgerline.app.domain.usecase.UnlockVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.CharBuffer
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
) : ViewModel() {
    private val _state = MutableStateFlow<UnlockUiState>(UnlockUiState.Idle)
    val state: StateFlow<UnlockUiState> = _state

    fun unlock(passphrase: CharArray) {
        viewModelScope.launch {
            _state.value = UnlockUiState.Working
            val session = sessionStore.load() ?: run { _state.value = UnlockUiState.Error("no session"); return@launch }
            val bytes = charsToUtf8(passphrase)
            val result = withContext(Dispatchers.Default) { // Argon2id is CPU-heavy
                UnlockVault(crypto, holder).withPassphrase(VaultRepository(session), bytes)
            }
            _state.value = when (result) {
                is Outcome.Ok -> UnlockUiState.Unlocked
                is Outcome.Err -> when (result.kind) {
                    ErrorKind.WRONG_PASSPHRASE -> UnlockUiState.Error("wrong")
                    ErrorKind.NOT_CONFIGURED -> UnlockUiState.NotConfigured
                    else -> UnlockUiState.Error(result.kind.name)
                }
            }
            passphrase.fill(' ')
        }
    }

    private fun charsToUtf8(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
        return ByteArray(bb.remaining()).also { bb.get(it) }
    }
}
