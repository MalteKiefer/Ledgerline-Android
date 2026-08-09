package de.ledgerline.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.theme.HeroIcon
import de.ledgerline.app.ui.theme.LedgerlineBackground
import de.ledgerline.app.ui.theme.PrimaryGradientButton
import javax.crypto.Cipher

/**
 * Login screen (replaces QR pairing): the user types the server URL + email + password, and a 2FA
 * code when the server asks for it. [authorize] runs the CryptoObject-bound biometric that seals the
 * returned token; [onLoggedIn] advances to the lock/home flow.
 */
@Composable
fun LoginScreen(
    authorize: suspend (Cipher) -> Cipher?,
    onLoggedIn: () -> Unit,
    vm: LoginViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("https://") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }

    val canSubmit = !state.submitting && url.length > 10 && email.isNotBlank() && password.isNotBlank() &&
        (!state.twoFactor || code.isNotBlank())

    LedgerlineBackground {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            HeroIcon(Icons.Outlined.Login)
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(stringResource(R.string.welcome_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text(stringResource(R.string.login_url)) },
                singleLine = true, enabled = !state.twoFactor,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text(stringResource(R.string.login_email)) },
                singleLine = true, enabled = !state.twoFactor,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(stringResource(R.string.login_password)) },
                singleLine = true, enabled = !state.twoFactor,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.twoFactor) {
                Text(stringResource(R.string.login_2fa_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text(stringResource(R.string.login_2fa_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Spacer(Modifier.height(8.dp))
            PrimaryGradientButton(
                text = stringResource(R.string.login_submit),
                enabled = canSubmit,
                onClick = { vm.submit(url, email, password, code, authorize, onLoggedIn) },
                modifier = Modifier.height(52.dp),
            )
        }
    }
}
