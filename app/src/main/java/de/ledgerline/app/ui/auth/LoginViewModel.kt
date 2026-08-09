package de.ledgerline.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.R
import de.ledgerline.app.data.LoginOutcome
import de.ledgerline.app.data.LoginRepository
import de.ledgerline.app.data.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * Direct-login flow (replaces QR pairing): URL + email + password (+ optional 2FA). On success the
 * device-scoped token is sealed to the keystore via the biometric-authorised [Cipher] (same store as
 * pairing used), then the caller advances to the lock/home flow.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginRepository: LoginRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {

    data class UiState(
        val submitting: Boolean = false,
        val twoFactor: Boolean = false,
        val errorRes: Int? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun submit(
        baseUrl: String,
        email: String,
        password: String,
        code: String,
        authorize: suspend (Cipher) -> Cipher?,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(submitting = true, errorRes = null)
            when (val r = loginRepository.login(baseUrl, email, password, code.ifBlank { null })) {
                is LoginOutcome.Success -> {
                    // Seal the token behind one biometric prompt; false = auth cancelled.
                    if (sessionStore.save(r.session, authorize)) onSuccess()
                    else _state.value = _state.value.copy(submitting = false, errorRes = R.string.login_error_auth)
                }
                LoginOutcome.TwoFactorRequired -> {
                    // The server returns two_factor:true both for the first prompt and for a wrong code;
                    // if we were already showing the 2FA field, the just-entered code was wrong.
                    val wrongCode = _state.value.twoFactor
                    _state.value = UiState(twoFactor = true, errorRes = if (wrongCode) R.string.login_error_invalid else null)
                }
                LoginOutcome.InvalidCredentials -> _state.value = _state.value.copy(submitting = false, errorRes = R.string.login_error_invalid)
                LoginOutcome.NotHttps -> _state.value = _state.value.copy(submitting = false, errorRes = R.string.login_error_https)
                LoginOutcome.NetworkError -> _state.value = _state.value.copy(submitting = false, errorRes = R.string.login_error_network)
            }
        }
    }
}
