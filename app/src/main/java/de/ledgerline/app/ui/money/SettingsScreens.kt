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

private sealed interface SettingsSub {
    data object Hub : SettingsSub
    data object Devices : SettingsSub
    data object Notifications : SettingsSub
    data object About : SettingsSub
}

/** Settings hub with internal sub-navigation (devices / notifications / about). */
@Composable
fun MoneySettingsScreen(onBack: () -> Unit, onLoggedOut: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    var sub by remember { mutableStateOf<SettingsSub>(SettingsSub.Hub) }
    when (sub) {
        SettingsSub.Devices -> DevicesScreen(vm) { sub = SettingsSub.Hub }
        SettingsSub.Notifications -> NotificationsScreen(vm) { sub = SettingsSub.Hub }
        SettingsSub.About -> AboutScreen { sub = SettingsSub.Hub }
        SettingsSub.Hub -> SettingsHub(vm, onBack, onLoggedOut, open = { sub = it })
    }
}

@Composable
private fun SettingsHub(vm: AccountViewModel, onBack: () -> Unit, onLoggedOut: () -> Unit, open: (SettingsSub) -> Unit) {
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

            SectionLabel(stringResource(R.string.settings_account))
            HubRow(stringResource(R.string.settings_devices)) { open(SettingsSub.Devices) }
            HubRow(stringResource(R.string.settings_notifications)) { open(SettingsSub.Notifications) }
            HubRow(stringResource(R.string.settings_about)) { open(SettingsSub.About) }

            SectionLabel(stringResource(R.string.settings_security))
            HubRow(stringResource(R.string.settings_lock_now)) { vm.lockNow() }

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
private fun AboutScreen(onBack: () -> Unit) {
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.settings_about), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(BuildConfig.GIT_SHA, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
