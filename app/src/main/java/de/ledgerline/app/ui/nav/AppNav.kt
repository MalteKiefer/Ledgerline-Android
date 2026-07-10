package de.ledgerline.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import de.ledgerline.app.data.SessionStore
import de.ledgerline.app.ui.onboarding.WelcomeScreen
import de.ledgerline.app.ui.pairing.PairingScreen
import de.ledgerline.app.ui.unlock.UnlockScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Destination { LOADING, WELCOME, PAIRING, UNLOCK, HOME }

@HiltViewModel
class RootViewModel @Inject constructor(private val sessionStore: SessionStore) : ViewModel() {
    private val _dest = MutableStateFlow(Destination.LOADING)
    val dest: StateFlow<Destination> = _dest

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

    fun toPairing() { _dest.value = Destination.PAIRING }
    fun toUnlock() { _dest.value = Destination.UNLOCK }
    fun toHome() { _dest.value = Destination.HOME }
}

/**
 * Root flow gate: pairing vs unlock vs home. [authGate] runs the app-lock
 * biometric/device-credential prompt (needs the Activity), threaded into the
 * screens that touch the auth-gated keystore key.
 */
@Composable
fun AppNav(
    authGate: suspend () -> Boolean,
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
        Destination.PAIRING -> PairingScreen(authGate = authGate, initialPairLink = initialPairLink, onPaired = { vm.toUnlock() })
        Destination.UNLOCK -> UnlockScreen(authGate = authGate, onUnlocked = { vm.toHome() })
        Destination.HOME -> HomePlaceholder()
    }
}

@Composable
private fun HomePlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Vault unlocked — Phase 2 starts here.")
    }
}
