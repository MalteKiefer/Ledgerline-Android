package de.ledgerline.app.ui.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.BuildConfig
import de.ledgerline.app.R
import de.ledgerline.app.data.ThemeMode
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.theme.cardSurface
import kotlinx.coroutines.launch

private sealed interface SettingsSub {
    data object Hub : SettingsSub
    data object Devices : SettingsSub
    data object Notifications : SettingsSub
    data object Security : SettingsSub
    data object About : SettingsSub
}

/** Settings hub with internal sub-navigation (devices / notifications / about). [onBack] is null when
 *  the hub is a root nav tab (no back affordance), non-null when pushed. */
@Composable
fun MoneySettingsScreen(onBack: (() -> Unit)? = null, onLoggedOut: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    var sub by remember { mutableStateOf<SettingsSub>(SettingsSub.Hub) }
    when (sub) {
        SettingsSub.Devices -> DevicesScreen(vm) { sub = SettingsSub.Hub }
        SettingsSub.Notifications -> NotificationsScreen(vm) { sub = SettingsSub.Hub }
        SettingsSub.Security -> SecurityScreen(vm, onLoggedOut) { sub = SettingsSub.Hub }
        SettingsSub.About -> AboutScreen { sub = SettingsSub.Hub }
        SettingsSub.Hub -> SettingsHub(vm, onBack, onLoggedOut, open = { sub = it })
    }
}

@Composable
private fun SettingsHub(vm: AccountViewModel, onBack: (() -> Unit)?, onLoggedOut: () -> Unit, open: (SettingsSub) -> Unit) {
    val me by vm.me.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.more_settings), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            me?.let { u ->
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(u.name ?: "—", style = MaterialTheme.typography.titleMedium)
                    u.email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            SectionLabel(stringResource(R.string.settings_appearance))
            Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
                ThemeRow(stringResource(R.string.theme_system), theme == ThemeMode.SYSTEM) { vm.setTheme(ThemeMode.SYSTEM) }
                ThemeRow(stringResource(R.string.theme_light), theme == ThemeMode.LIGHT) { vm.setTheme(ThemeMode.LIGHT) }
                ThemeRow(stringResource(R.string.theme_dark), theme == ThemeMode.DARK) { vm.setTheme(ThemeMode.DARK) }
            }

            var langOpen by remember { mutableStateOf(false) }
            val curLang = remember { vm.currentLanguageTag() }
            HubRow(stringResource(R.string.settings_language) + " · " + languageLabel(curLang)) { langOpen = true }
            if (langOpen) LanguageDialog(current = curLang, onPick = { vm.setLanguage(it); langOpen = false }, onDismiss = { langOpen = false })

            SectionLabel(stringResource(R.string.settings_files))
            val maxVersions by vm.fileMaxVersions.collectAsStateWithLifecycle()
            MaxVersionsRow(current = maxVersions, onPick = { vm.setFileMaxVersions(it) })

            SectionLabel(stringResource(R.string.settings_account))
            HubRow(stringResource(R.string.settings_devices)) { open(SettingsSub.Devices) }
            HubRow(stringResource(R.string.settings_notifications)) { open(SettingsSub.Notifications) }
            HubRow(stringResource(R.string.settings_about)) { open(SettingsSub.About) }

            SectionLabel(stringResource(R.string.settings_security))
            HubRow(stringResource(R.string.settings_lock_now)) { vm.lockNow() }
            HubRow(stringResource(R.string.security_title)) { open(SettingsSub.Security) }

            SectionLabel(stringResource(R.string.settings_account), danger = true)
            TextButton(onClick = { vm.logout(onLoggedOut) }) {
                Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ThemeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onSelect).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HubRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).cardSurface(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun languageLabel(tag: String): String = when (tag) {
    "en" -> "English"
    "de" -> "Deutsch"
    "ru" -> "Русский"
    "" -> stringResource(R.string.lang_system)
    else -> tag
}

@Composable
private fun LanguageDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val opts = listOf("" to stringResource(R.string.lang_system), "en" to "English", "de" to "Deutsch", "ru" to "Русский")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                opts.forEach { (tag, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(current == tag, onClick = { onPick(tag) }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = current == tag, onClick = { onPick(tag) })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MaxVersionsRow(current: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.cardSurface(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_max_versions), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(current.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(5, 10, 20, 50, 100).forEach { n ->
                DropdownMenuItem(text = { Text(n.toString()) }, onClick = { open = false; onPick(n) })
            }
        }
    }
}

@Composable
private fun DevicesScreen(vm: AccountViewModel, onBack: () -> Unit) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadDevices() }
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.settings_devices), onBack = onBack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (devices.isEmpty()) EmptyState(stringResource(R.string.devices_empty))
            else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(devices, key = { it.id }) { d ->
                    Row(Modifier.fillMaxWidth().cardSurface(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name + if (d.current) " · " + stringResource(R.string.device_this) else "", style = MaterialTheme.typography.bodyLarge)
                            if (d.meta.isNotBlank()) Text(d.meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!d.current) {
                            IconButton(onClick = { vm.wipeDevice(d.id) }) { Icon(Icons.Outlined.DeleteForever, stringResource(R.string.device_wipe), tint = MaterialTheme.colorScheme.error) }
                            IconButton(onClick = { vm.revokeDevice(d.id) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.device_revoke)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationsScreen(vm: AccountViewModel, onBack: () -> Unit) {
    val items by vm.notifications.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadNotifications() }
    AppScaffold(topBar = {
        AppTopBar(title = stringResource(R.string.settings_notifications), onBack = onBack, actions = {
            TextButton(onClick = { vm.markAllRead() }) { Text(stringResource(R.string.notifications_mark_all)) }
        })
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (items.isEmpty()) EmptyState(stringResource(R.string.notifications_empty))
            else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { n ->
                    Column(Modifier.fillMaxWidth().clickable { if (!n.read) vm.markRead(n.id) }.cardSurface(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(n.title, style = MaterialTheme.typography.bodyLarge, color = if (n.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        n.body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityScreen(vm: AccountViewModel, onLoggedOut: () -> Unit, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var curPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var twoFa by remember { mutableStateOf<de.ledgerline.app.data.remote.dto.TwoFactorQrResponse?>(null) }
    var twoFaCode by remember { mutableStateOf("") }
    var recoveryCodes by remember { mutableStateOf<List<String>>(emptyList()) }
    var delEmail by remember { mutableStateOf("") }

    // SAF: write the export bytes to a user-chosen location (no FileProvider needed).
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) scope.launch {
            val bytes = vm.exportAccount()
            if (bytes != null) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                msg = ctx.getString(R.string.security_export_done)
            } else msg = ctx.getString(R.string.security_failed)
        }
    }

    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.security_title), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            SectionLabel(stringResource(R.string.security_password))
            Field(curPw, { curPw = it }, R.string.security_current_password)
            Field(newPw, { newPw = it }, R.string.security_new_password)
            TextButton(enabled = curPw.isNotBlank() && newPw.length >= 12, onClick = {
                vm.changePassword(curPw, newPw) { ok -> msg = ctx.getString(if (ok) R.string.security_password_changed else R.string.security_failed); if (ok) { curPw = ""; newPw = "" } }
            }) { Text(stringResource(R.string.security_change_password)) }

            SectionLabel(stringResource(R.string.security_2fa))
            if (twoFa == null) {
                TextButton(onClick = { scope.launch { twoFa = vm.twoFactorBegin() } }) { Text(stringResource(R.string.security_2fa_enable)) }
                TextButton(onClick = { vm.twoFactorDisable { msg = ctx.getString(if (it) R.string.security_2fa_disabled else R.string.security_failed) } }) {
                    Text(stringResource(R.string.security_2fa_disable))
                }
            } else {
                twoFa?.secret?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.security_2fa_secret) + " " + it, style = MaterialTheme.typography.bodyMedium) }
                Field(twoFaCode, { twoFaCode = it }, R.string.security_2fa_code)
                TextButton(enabled = twoFaCode.length >= 6, onClick = {
                    vm.twoFactorConfirm(twoFaCode) { ok -> msg = ctx.getString(if (ok) R.string.security_2fa_enabled else R.string.security_failed); if (ok) { twoFa = null; twoFaCode = "" } }
                }) { Text(stringResource(R.string.security_2fa_confirm)) }
            }

            SectionLabel(stringResource(R.string.security_recovery_codes))
            if (recoveryCodes.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    recoveryCodes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { recoveryCodes = vm.recoveryCodes() } }) { Text(stringResource(R.string.security_recovery_show)) }
                TextButton(onClick = { scope.launch { recoveryCodes = vm.regenerateRecoveryCodes() } }) { Text(stringResource(R.string.security_recovery_regenerate)) }
            }

            SectionLabel(stringResource(R.string.security_data))
            TextButton(onClick = { exportLauncher.launch("ledgerline-export.zip") }) { Text(stringResource(R.string.security_export)) }

            SectionLabel(stringResource(R.string.security_danger), danger = true)
            Field(delEmail, { delEmail = it }, R.string.security_delete_confirm_email)
            TextButton(enabled = delEmail.isNotBlank(), onClick = {
                vm.deleteAccount(delEmail) { ok -> if (ok) onLoggedOut() else msg = ctx.getString(R.string.security_failed) }
            }) { Text(stringResource(R.string.security_delete_account), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.settings_about), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(BuildConfig.GIT_SHA, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
