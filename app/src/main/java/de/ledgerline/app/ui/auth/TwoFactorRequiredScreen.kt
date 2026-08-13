package de.ledgerline.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import de.ledgerline.app.R
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.money.AccountViewModel
import de.ledgerline.app.ui.theme.PrimaryGradientButton
import kotlinx.coroutines.launch

/**
 * Force-2FA gate: the workspace enabled `force_2fa`, so the server 403s every gated request with
 * `two_factor_required` until this device enrolls a second factor. Hosts the enrollment inline
 * (password step-up → TOTP secret → confirm) so the user is never stuck; on success we return HOME.
 */
@Composable
fun TwoFactorRequiredScreen(
    onEnrolled: () -> Unit,
    onLoggedOut: () -> Unit,
    vm: AccountViewModel = hiltViewModel(),
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var pw by remember { mutableStateOf("") }
    var qr by remember { mutableStateOf<de.ledgerline.app.data.remote.dto.TwoFactorQrResponse?>(null) }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }

    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.two_factor_required_title), onBack = null) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.two_factor_required_body), style = MaterialTheme.typography.bodyMedium)
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (qr == null) {
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.security_step_up_pw)) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                PrimaryGradientButton(
                    text = stringResource(R.string.security_2fa_enable),
                    enabled = pw.isNotBlank(),
                    onClick = {
                        scope.launch {
                            qr = vm.twoFactorBegin(pw)
                            if (qr == null) msg = ctx.getString(R.string.security_failed)
                        }
                    },
                )
            } else {
                SectionLabel(stringResource(R.string.security_2fa))
                qr?.secret?.takeIf { it.isNotBlank() }?.let {
                    Text(stringResource(R.string.security_2fa_secret) + " " + it, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                }
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.security_2fa_code)) },
                )
                PrimaryGradientButton(
                    text = stringResource(R.string.security_2fa_confirm),
                    enabled = code.isNotBlank(),
                    onClick = {
                        vm.twoFactorConfirm(code) { ok ->
                            if (ok) onEnrolled() else msg = ctx.getString(R.string.security_failed)
                        }
                    },
                )
            }

            TextButton(onClick = { vm.logout(onLoggedOut) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
