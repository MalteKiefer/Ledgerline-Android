package de.ledgerline.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.AppLockState
import de.ledgerline.app.core.AuthEventBus
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.data.AccountRepository
import de.ledgerline.app.data.SessionStore
import de.ledgerline.app.data.files.FilesRepository
import de.ledgerline.app.data.finance.FinanceRepository
import de.ledgerline.app.domain.usecase.ForceLogout
import de.ledgerline.app.ui.auth.LoginScreen
import de.ledgerline.app.ui.lock.AppLockScreen
import de.ledgerline.app.ui.shell.AppShell
import de.ledgerline.app.ui.onboarding.WelcomeScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Destination { LOADING, WELCOME, LOGIN, LOCK, HOME }

/**
 * Root flow: WELCOME → LOGIN (URL + email + password + optional 2FA) → LOCK (biometric session read)
 * → HOME. There is no QR pairing and no zero-knowledge vault/passphrase — the device-scoped Sanctum
 * token (unsealed by one biometric) grants data access directly. [AppLockState] is the in-memory gate;
 * backgrounding/idle/logout locks.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val sessionStore: SessionStore,
    private val appLockState: AppLockState,
    private val sessionHolder: SessionHolder,
    private val authEventBus: AuthEventBus,
    private val forceLogout: ForceLogout,
    private val financeRepository: FinanceRepository,
    private val filesRepository: FilesRepository,
    private val notesRepository: de.ledgerline.app.data.notes.NotesRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {
    private val _dest = MutableStateFlow(Destination.LOADING)
    val dest: StateFlow<Destination> = _dest

    val unlocked: StateFlow<Boolean> = appLockState.unlocked

    fun start() {
        viewModelScope.launch {
            _dest.value = if (sessionStore.exists()) Destination.LOCK else Destination.WELCOME
        }
    }

    init {
        // Locked while past the gate (backgrounded/idle) → back to the lock screen.
        viewModelScope.launch {
            appLockState.unlocked.collect { unlocked ->
                if (!unlocked && _dest.value == Destination.HOME) _dest.value = Destination.LOCK
            }
        }
        // Revoked token (authenticated 401) or remote wipe → erase local state + re-pair.
        viewModelScope.launch { authEventBus.unauthorized.collect { wipeToWelcome() } }
        viewModelScope.launch { authEventBus.wipe.collect { wipeToWelcome() } }
    }

    private suspend fun wipeToWelcome() {
        forceLogout.invoke()
        appLockState.lock()
        financeRepository.clear()
        filesRepository.clear()
        notesRepository.clear()
        sessionHolder.clear()
        _dest.value = Destination.WELCOME
    }

    fun toLogin() { _dest.value = Destination.LOGIN }
    fun toLock() { appLockState.lock(); _dest.value = Destination.LOCK }
    fun toHome() {
        _dest.value = Destination.HOME
        viewModelScope.launch { accountRepository.me() } // fires the remote-wipe kill switch
    }
    fun toWelcome() { _dest.value = Destination.WELCOME }
}

/**
 * Root gate. [authorize] runs the app-lock CryptoObject-bound biometric on a keystore cipher and
 * returns the authorised cipher (used by pairing to seal the token and by the lock screen to read it).
 */
@Composable
fun AppNav(
    authorize: suspend (javax.crypto.Cipher) -> javax.crypto.Cipher?,
    vm: RootViewModel = hiltViewModel(),
) {
    val dest by vm.dest.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.start() }

    when (dest) {
        Destination.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Destination.WELCOME -> WelcomeScreen(onGetStarted = { vm.toLogin() })
        Destination.LOGIN -> LoginScreen(authorize = authorize, onLoggedIn = { vm.toHome() })
        Destination.LOCK -> AppLockScreen(authorize = authorize, onUnlocked = { vm.toHome() })
        Destination.HOME -> {
            val unlocked by vm.unlocked.collectAsStateWithLifecycle()
            if (unlocked) {
                AppShell(onDisconnected = { vm.toWelcome() })
            } else {
                AppLockScreen(authorize = authorize, onUnlocked = { vm.toHome() })
            }
        }
    }
}
