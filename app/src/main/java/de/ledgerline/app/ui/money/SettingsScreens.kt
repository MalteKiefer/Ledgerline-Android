package de.ledgerline.app.ui.money

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import de.ledgerline.app.ui.common.LedgerRow
import de.ledgerline.app.ui.common.ListSectionCard
import de.ledgerline.app.ui.common.RowChevron
import de.ledgerline.app.ui.common.RowDivider
import de.ledgerline.app.ui.common.RowMeta
import de.ledgerline.app.ui.common.SoftIconChip
import de.ledgerline.app.ui.theme.Brand
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
        SettingsSub.About -> AboutScreen(vm) { sub = SettingsSub.Hub }
        SettingsSub.Hub -> SettingsHub(vm, onBack, onLoggedOut, open = { sub = it })
    }
}

@Composable
private fun SettingsHub(vm: AccountViewModel, onBack: (() -> Unit)?, onLoggedOut: () -> Unit, open: (SettingsSub) -> Unit) {
    val me by vm.me.collectAsStateWithLifecycle()
    val avatar by vm.avatar.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val maxVersions by vm.fileMaxVersions.collectAsStateWithLifecycle()
    var langOpen by remember { mutableStateOf(false) }
    var themeOpen by remember { mutableStateOf(false) }
    var maxOpen by remember { mutableStateOf(false) }
    val curLang = remember { vm.currentLanguageTag() }

    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.more_settings), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            ProfileHeader(me?.name, me?.email, avatar)

            SectionLabel(stringResource(R.string.settings_appearance))
            ListSectionCard {
                SettingRow(stringResource(R.string.settings_theme), themeLabel(theme), Icons.Outlined.DarkMode, Brand.tintViolet) { themeOpen = true }
                RowDivider()
                SettingRow(stringResource(R.string.settings_language), languageLabel(curLang), Icons.Outlined.Translate, Brand.tintBlue) { langOpen = true }
            }

            SectionLabel(stringResource(R.string.settings_files))
            ListSectionCard {
                SettingRow(stringResource(R.string.settings_max_versions), maxVersions.toString(), Icons.Outlined.Folder, Brand.tintTeal) { maxOpen = true }
            }

            SectionLabel(stringResource(R.string.settings_account))
            ListSectionCard {
                SettingRow(stringResource(R.string.settings_devices), null, Icons.Outlined.Devices, Brand.tintBlue) { open(SettingsSub.Devices) }
                RowDivider()
                SettingRow(stringResource(R.string.settings_notifications), null, Icons.Outlined.Notifications, Brand.tintOrange) { open(SettingsSub.Notifications) }
                RowDivider()
                SettingRow(stringResource(R.string.security_title), null, Icons.Outlined.Shield, Brand.tintGreen) { open(SettingsSub.Security) }
                RowDivider()
                SettingRow(stringResource(R.string.settings_about), null, Icons.Outlined.Info, Brand.tintGray) { open(SettingsSub.About) }
            }

            SectionLabel(stringResource(R.string.settings_security))
            ListSectionCard {
                SettingRow(stringResource(R.string.settings_lock_now), null, Icons.Outlined.Lock, Brand.tintViolet) { vm.lockNow() }
                RowDivider()
                LedgerRow(
                    title = stringResource(R.string.settings_logout),
                    leading = { SoftIconChip(Icons.AutoMirrored.Outlined.Logout, tint = MaterialTheme.colorScheme.error) },
                    onClick = { vm.logout(onLoggedOut) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (themeOpen) ThemeDialog(current = theme, onPick = { vm.setTheme(it); themeOpen = false }, onDismiss = { themeOpen = false })
    if (langOpen) LanguageDialog(current = curLang, onPick = { vm.setLanguage(it); langOpen = false }, onDismiss = { langOpen = false })
    if (maxOpen) MaxVersionsDialog(current = maxVersions, onPick = { vm.setFileMaxVersions(it); maxOpen = false }, onDismiss = { maxOpen = false })
}

/** Account header: circular avatar (or gradient initial) + name + email. */
@Composable
private fun ProfileHeader(name: String?, email: String?, avatar: ImageBitmap?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Brand.accentGradient), contentAlignment = Alignment.Center) {
            if (avatar != null) {
                Image(avatar, contentDescription = null, modifier = Modifier.size(56.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            } else {
                Text((name?.trim()?.firstOrNull()?.uppercase() ?: "?"), style = MaterialTheme.typography.headlineSmall, color = androidx.compose.ui.graphics.Color.White)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(name ?: "—", style = MaterialTheme.typography.titleMedium)
            email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** A grouped-list settings row: tinted icon chip, label, optional current-value, chevron. */
@Composable
private fun SettingRow(label: String, value: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    LedgerRow(
        title = label,
        leading = { SoftIconChip(icon, tint = tint) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                value?.let { RowMeta(it) }
                RowChevron()
            }
        },
        onClick = onClick,
    )
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
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark },
)

@Composable
private fun ThemeDialog(current: ThemeMode, onPick: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column {
                ThemeRow(stringResource(R.string.theme_system), current == ThemeMode.SYSTEM) { onPick(ThemeMode.SYSTEM) }
                ThemeRow(stringResource(R.string.theme_light), current == ThemeMode.LIGHT) { onPick(ThemeMode.LIGHT) }
                ThemeRow(stringResource(R.string.theme_dark), current == ThemeMode.DARK) { onPick(ThemeMode.DARK) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MaxVersionsDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_max_versions)) },
        text = {
            Column {
                listOf(5, 10, 20, 50, 100).forEach { n ->
                    Row(
                        Modifier.fillMaxWidth().selectable(current == n, onClick = { onPick(n) }).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = current == n, onClick = { onPick(n) })
                        Text(n.toString(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
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
private fun AboutScreen(vm: AccountViewModel, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sourceUrl = stringResource(R.string.about_source_url)
    val server = remember { vm.serverUrl() }
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.settings_about), onBack = onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.Image(
                painterResource(R.drawable.ic_ledgerline_logo),
                contentDescription = null,
                modifier = Modifier.size(88.dp),
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.about_blurb),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            // Version + build metadata card.
            ListSectionCard {
                AboutInfoRow(stringResource(R.string.about_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                RowDivider()
                val dirty = if (BuildConfig.GIT_DIRTY) "*" else ""
                AboutInfoRow(stringResource(R.string.about_build), "${BuildConfig.GIT_SHA}$dirty · ${BuildConfig.BUILD_DATE}")
                server?.let {
                    RowDivider()
                    AboutInfoRow(stringResource(R.string.about_server), it.removePrefix("https://").removePrefix("http://"))
                }
            }

            // Links.
            ListSectionCard {
                LedgerRow(
                    title = stringResource(R.string.about_source),
                    subtitle = sourceUrl.removePrefix("https://"),
                    leading = { SoftIconChip(Icons.Outlined.Code, tint = Brand.tintViolet) },
                    trailing = { RowChevron() },
                    onClick = { de.ledgerline.app.ui.common.openUrl(ctx, sourceUrl, chooser = false) },
                )
            }

            Text(
                stringResource(R.string.about_made_by),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
