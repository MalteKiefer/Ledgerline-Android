package de.ledgerline.app.ui.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import kotlinx.coroutines.launch

/**
 * Vault unlock screen. Tapping Unlock first runs the app-lock auth ([authGate]) —
 * this opens the keystore auth window so the sealed session can be read — then
 * derives the Vault Key from the passphrase.
 */
@Composable
fun UnlockScreen(
    vm: UnlockViewModel = hiltViewModel(),
    authGate: suspend () -> Boolean,
    onUnlocked: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var passphrase by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state) { if (state is UnlockUiState.Unlocked) onUnlocked() }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text(stringResource(R.string.unlock_passphrase)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val entered = passphrase.toCharArray()
                passphrase = ""
                scope.launch { if (authGate()) vm.unlock(entered) else entered.fill(' ') }
            },
            enabled = state != UnlockUiState.Working,
        ) {
            Text(stringResource(R.string.unlock_button))
        }
        when (state) {
            is UnlockUiState.Working -> { Spacer(Modifier.height(12.dp)); CircularProgressIndicator() }
            is UnlockUiState.NotConfigured -> Text(stringResource(R.string.unlock_not_configured), color = MaterialTheme.colorScheme.error)
            is UnlockUiState.Error -> Text(stringResource(R.string.unlock_error), color = MaterialTheme.colorScheme.error)
            else -> {}
        }
    }
}
