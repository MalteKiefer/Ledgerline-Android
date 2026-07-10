package de.ledgerline.app.ui.unlock

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import kotlinx.coroutines.launch

/**
 * Vault unlock screen. Tapping Unlock reads the sealed session, which triggers
 * exactly one CryptoObject-bound app-lock biometric ([authorize]) to authorize the
 * keystore decrypt, then derives the Vault Key from the passphrase.
 */
@Composable
fun UnlockScreen(
    vm: UnlockViewModel = hiltViewModel(),
    authorize: suspend (javax.crypto.Cipher) -> javax.crypto.Cipher?,
    onUnlocked: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var passphrase by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state) { if (state is UnlockUiState.Unlocked) onUnlocked() }
    // Drop any stale error/not-configured message left from a previous attempt or a
    // forced logout when this screen is (re-)entered.
    LaunchedEffect(Unit) { vm.reset() }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_ledgerline_logo),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.unlock_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.unlock_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it; vm.reset() },
                    label = { Text(stringResource(R.string.unlock_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val entered = passphrase.toCharArray()
                        passphrase = ""
                        // Exactly one biometric, triggered inside load() via authorize.
                        scope.launch { vm.unlock(entered, authorize) }
                    },
                    enabled = state != UnlockUiState.Working,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(stringResource(R.string.unlock_button), style = MaterialTheme.typography.labelLarge)
                }
                when (state) {
                    is UnlockUiState.Working -> {
                        Spacer(Modifier.height(16.dp))
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    is UnlockUiState.NotConfigured -> {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.unlock_not_configured),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    is UnlockUiState.Error -> {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.unlock_error),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}
