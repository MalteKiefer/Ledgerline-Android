package de.ledgerline.app.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.data.PairingRepository
import de.ledgerline.app.data.SessionStore
import de.ledgerline.app.domain.model.PairingState
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.usecase.ClaimAndPollPairing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: PairingRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    fun startPairing(baseUrl: String, code: String, deviceName: String) {
        viewModelScope.launch {
            ClaimAndPollPairing(repository).run(baseUrl, code, deviceName).collect { s ->
                _state.value = s
            }
        }
    }

    /**
     * Persist the paired session as a keystore-sealed blob. [authorize] runs the
     * single CryptoObject-bound biometric that authorizes the keystore seal.
     * @return true on success, false if the auth was cancelled/failed.
     */
    suspend fun persist(session: Session, authorize: suspend (Cipher) -> Cipher?): Boolean {
        return sessionStore.save(session, authorize)
    }
}
