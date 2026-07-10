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
import de.ledgerline.app.core.AuthEventBus
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.SessionStore
import de.ledgerline.app.domain.usecase.ForceLogout
import de.ledgerline.app.ui.onboarding.WelcomeScreen
import de.ledgerline.app.ui.pairing.PairingScreen
import de.ledgerline.app.ui.unlock.UnlockScreen
import de.ledgerline.app.ui.workspace.WorkspaceScaffold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Destination { LOADING, WELCOME, PAIRING, UNLOCK, HOME }

@HiltViewModel
class RootViewModel @Inject constructor(
    private val sessionStore: SessionStore,
    private val vaultKeyHolder: VaultKeyHolder,
    private val authEventBus: AuthEventBus,
    private val forceLogout: ForceLogout,
) : ViewModel() {
    private val _dest = MutableStateFlow(Destination.LOADING)
    val dest: StateFlow<Destination> = _dest

    val unlocked: StateFlow<Boolean> = vaultKeyHolder.unlocked

    /**
     * Decide the start destination. If a session already exists, go straight to
     * unlock. Otherwise show onboarding — unless a pairing deep link launched the
     * app, in which case jump directly to pairing (scanning).
     */
    fun start(hasPairLink: Boolean) {
        viewModelScope.launch {
            // exists() only checks for the sealed blob's presence — no auth/decrypt.
            _dest.value = when {
                sessionStore.exists() -> Destination.UNLOCK
                hasPairLink -> Destination.PAIRING
                else -> Destination.WELCOME
            }
        }
    }

    init {
        // If the vault key is wiped while we're past unlock, drop back to UNLOCK.
        viewModelScope.launch {
            vaultKeyHolder.unlocked.collect { unlocked ->
                if (!unlocked && _dest.value == Destination.HOME) _dest.value = Destination.UNLOCK
            }
        }
        // Any authenticated 401 (revoked token) → wipe everything and re-pair.
        viewModelScope.launch {
            authEventBus.unauthorized.collect {
                forceLogout.invoke()
                _dest.value = Destination.WELCOME
            }
        }
    }

    fun toPairing() { _dest.value = Destination.PAIRING }
    fun toUnlock() { _dest.value = Destination.UNLOCK }
    fun toHome() { _dest.value = Destination.HOME }
    fun toWelcome() { _dest.value = Destination.WELCOME }
}

/**
 * Root flow gate: pairing vs unlock vs home. [authorize] runs the app-lock
 * CryptoObject-bound biometric/device-credential prompt on a keystore cipher (needs
 * the Activity) and returns the authorised cipher, threaded into the screens that
 * touch the auth-gated keystore key. Exactly one prompt per session read/write.
 */
@Composable
fun AppNav(
    authorize: suspend (javax.crypto.Cipher) -> javax.crypto.Cipher?,
    initialPairLink: String? = null,
    vm: RootViewModel = hiltViewModel(),
) {
    val dest by vm.dest.collectAsStateWithLifecycle()

    // Resolve the start destination once the initial link is known.
    LaunchedEffect(Unit) { vm.start(hasPairLink = initialPairLink != null) }

    // A pairing link delivered while running (onNewIntent) routes to pairing,
    // unless a session already exists (then unlock takes precedence).
    LaunchedEffect(initialPairLink) {
        if (initialPairLink != null && dest == Destination.WELCOME) vm.toPairing()
    }

    when (dest) {
        Destination.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Destination.WELCOME -> WelcomeScreen(onGetStarted = { vm.toPairing() })
        Destination.PAIRING -> PairingScreen(authorize = authorize, initialPairLink = initialPairLink, onPaired = { vm.toUnlock() })
        Destination.UNLOCK -> UnlockScreen(authorize = authorize, onUnlocked = { vm.toHome() })
        Destination.HOME -> {
            val unlocked by vm.unlocked.collectAsStateWithLifecycle()
            if (unlocked) {
                WorkspaceScaffold(
                    onLockNow = { vm.toUnlock() },
                    onDisconnected = { vm.toWelcome() },
                )
            } else {
                UnlockScreen(authorize = authorize, onUnlocked = { vm.toHome() })
            }
        }
    }
}
