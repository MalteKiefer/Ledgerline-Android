package de.ledgerline.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import de.ledgerline.app.R
import de.ledgerline.app.core.AppLockState
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.data.SessionStore
import de.ledgerline.app.ui.theme.HeroIcon
import de.ledgerline.app.ui.theme.LedgerlineBackground
import de.ledgerline.app.ui.theme.PrimaryGradientButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

sealed interface LockUi {
    data object Idle : LockUi
    data object Working : LockUi
    data object Error : LockUi
}

/**
 * Biometric app-lock: one CryptoObject-bound prompt reads the Keystore-sealed session token into the
 * [SessionHolder] and unlocks the app. No passphrase / vault key (the ZK model was removed).
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val sessionStore: SessionStore,
    private val sessionHolder: SessionHolder,
    private val appLockState: AppLockState,
) : ViewModel() {
    private val _state = MutableStateFlow<LockUi>(LockUi.Idle)
    val state: StateFlow<LockUi> = _state

    fun unlock(authorize: suspend (Cipher) -> Cipher?, onUnlocked: () -> Unit) {
        viewModelScope.launch {
            _state.value = LockUi.Working
            val session = sessionStore.load(authorize)
            if (session != null) {
                sessionHolder.set(session)
                appLockState.unlock()
                onUnlocked()
            } else {
                _state.value = LockUi.Error // cancelled or biometric failed
            }
        }
    }
}

@Composable
fun AppLockScreen(
    authorize: suspend (Cipher) -> Cipher?,
    onUnlocked: () -> Unit,
    vm: AppLockViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Prompt once on entry; the user can retry after a cancel/failure.
    LaunchedEffect(Unit) { vm.unlock(authorize, onUnlocked) }

    LedgerlineBackground {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            HeroIcon(icon = Icons.Outlined.Lock)
            Text(
                stringResource(R.string.applock_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                stringResource(R.string.applock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
            )
            PrimaryGradientButton(
                text = stringResource(if (state == LockUi.Error) R.string.applock_retry else R.string.applock_unlock),
                onClick = { vm.unlock(authorize, onUnlocked) },
                enabled = state != LockUi.Working,
                modifier = Modifier.width(240.dp),
            )
        }
    }
}
