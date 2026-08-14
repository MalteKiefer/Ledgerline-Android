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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    data object Admin : SettingsSub
    data object Paperless : SettingsSub
    data object GalleryBackup : SettingsSub
}

/** Settings hub with internal sub-navigation (devices / notifications / about). [onBack] is null when
 *  the hub is a root nav tab (no back affordance), non-null when pushed. */
@Composable
fun MoneySettingsScreen(
    onBack: (() -> Unit)? = null,
    onLoggedOut: () -> Unit,
    openNotifications: Boolean = false,
    onNotificationsOpened: () -> Unit = {},
    vm: AccountViewModel = hiltViewModel(),
) {
    var sub by remember { mutableStateOf<SettingsSub>(SettingsSub.Hub) }
    LaunchedEffect(openNotifications) {
        if (openNotifications) { sub = SettingsSub.Notifications; onNotificationsOpened() }
    }
    when (sub) {
        SettingsSub.Devices -> DevicesScreen(vm) { sub = SettingsSub.Hub }
        SettingsSub.Notifications -> NotificationsScreen(vm) { sub = SettingsSub.Hub }
        SettingsSub.Security -> SecurityScreen(vm, onLoggedOut) { sub = SettingsSub.Hub }
        SettingsSub.About -> AboutScreen(vm) { sub = SettingsSub.Hub }
        SettingsSub.Admin -> de.ledgerline.app.ui.admin.AdminScreen(onBack = { sub = SettingsSub.Hub })
        SettingsSub.Paperless -> PaperlessScreen(vm) { sub = SettingsSub.Hub }
        SettingsSub.GalleryBackup -> de.ledgerline.app.ui.gallery.GalleryBackupScreen(onBack = { sub = SettingsSub.Hub })
        SettingsSub.Hub -> SettingsHub(vm, onBack, onLoggedOut, open = { sub = it })
    }
}

@Composable
private fun SettingsHub(vm: AccountViewModel, onBack: (() -> Unit)?, onLoggedOut: () -> Unit, open: (SettingsSub) -> Unit) {
    val me by vm.me.collectAsStateWithLifecycle()
    val avatar by vm.avatar.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val maxVersions by vm.fileMaxVersions.collectAsStateWithLifecycle()
    val prefs by vm.displayPrefs.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var langOpen by remember { mutableStateOf(false) }
    var themeOpen by remember { mutableStateOf(false) }
    var maxOpen by remember { mutableStateOf(false) }
    var dateOpen by remember { mutableStateOf(false) }
    var tzOpen by remember { mutableStateOf(false) }
    val curLang = remember { vm.currentLanguageTag() }

    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.more_settings), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            ProfileHeader(me?.name, me?.email, avatar)

            SectionLabel(stringResource(R.string.settings_appearance))
            ListSectionCard {
                SettingRow(stringResource(R.string.settings_theme), themeLabel(theme), Icons.Outlined.DarkMode, Brand.tintViolet) { themeOpen = true }
                RowDivider()
                SettingRow(stringResource(R.string.settings_language), languageLabel(curLang), Icons.Outlined.Translate, Brand.tintBlue) { langOpen = true }
                RowDivider()
                SettingRow(stringResource(R.string.settings_date_format), dateFormatLabel(prefs.dateFormat), Icons.Outlined.CalendarMonth, Brand.tintTeal) { dateOpen = true }
                RowDivider()
                SettingRow(stringResource(R.string.settings_timezone), prefs.timezone.ifBlank { stringResource(R.string.settings_timezone_system) }, Icons.Outlined.Schedule, Brand.tintOrange) { tzOpen = true }
            }

            SectionLabel(stringResource(R.string.settings_files))
            ListSectionCard {
                SettingRow(stringResource(R.string.settings_max_versions), maxVersions.toString(), Icons.Outlined.Folder, Brand.tintTeal) { maxOpen = true }
                RowDivider()
                SettingRow(stringResource(R.string.files_reindex), null, Icons.Outlined.Search, Brand.tintBlue) {
                    vm.reindex { ok ->
                        android.widget.Toast.makeText(ctx, ctx.getString(if (ok) R.string.files_reindex_queued else R.string.files_save_failed), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }

            SectionLabel(stringResource(R.string.settings_account))
            ListSectionCard {
                SettingRow(stringResource(R.string.settings_devices), null, Icons.Outlined.Devices, Brand.tintBlue) { open(SettingsSub.Devices) }
                RowDivider()
                SettingRow(stringResource(R.string.settings_notifications), null, Icons.Outlined.Notifications, Brand.tintOrange) { open(SettingsSub.Notifications) }
                RowDivider()
                SettingRow(stringResource(R.string.security_title), null, Icons.Outlined.Shield, Brand.tintGreen) { open(SettingsSub.Security) }
                RowDivider()
                SettingRow(stringResource(R.string.settings_paperless), null, Icons.Outlined.Description, Brand.tintTeal) { open(SettingsSub.Paperless) }
                RowDivider()
                SettingRow(stringResource(R.string.gallery_backup), null, Icons.Outlined.CloudUpload, Brand.tintBlue) { open(SettingsSub.GalleryBackup) }
                RowDivider()
                SettingRow(stringResource(R.string.settings_about), null, Icons.Outlined.Info, Brand.tintGray) { open(SettingsSub.About) }
            }

            if (me?.groups?.contains("admin") == true) {
                SectionLabel(stringResource(R.string.admin_title))
                ListSectionCard {
                    SettingRow(stringResource(R.string.admin_title), null, Icons.Outlined.AdminPanelSettings, Brand.accent) { open(SettingsSub.Admin) }
                }
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
    if (dateOpen) DateFormatDialog(current = prefs.dateFormat, onPick = { vm.setDateFormat(it); dateOpen = false }, onDismiss = { dateOpen = false })
    if (tzOpen) TimezoneDialog(current = prefs.timezone, onPick = { vm.setTimezone(it); tzOpen = false }, onDismiss = { tzOpen = false })
}

@Composable
private fun TimezoneDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val zones = remember { java.time.ZoneId.getAvailableZoneIds().filter { '/' in it }.sorted() }
    var q by remember { mutableStateOf("") }
    val filtered = remember(q) { if (q.isBlank()) zones else zones.filter { it.contains(q, ignoreCase = true) } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_timezone)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text(stringResource(R.string.action_search)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth()) {
                    item(key = "__system") {
                        Row(Modifier.fillMaxWidth().clickable { onPick("") }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = current.isBlank(), onClick = { onPick("") })
                            Text(stringResource(R.string.settings_timezone_system), Modifier.padding(start = 8.dp))
                        }
                    }
                    items(filtered, key = { it }) { zone ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(zone) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = zone == current, onClick = { onPick(zone) })
                            Text(zone, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

private val DATE_FORMATS = listOf("system", "dmy", "dmy_dot", "mdy", "ymd")

@Composable
private fun dateFormatLabel(fmt: String): String = when (fmt) {
    "dmy" -> "31/12/2026"
    "dmy_dot" -> "31.12.2026"
    "mdy" -> "12/31/2026"
    "ymd" -> "2026-12-31"
    else -> stringResource(R.string.settings_date_format_system)
}

@Composable
private fun DateFormatDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_date_format)) },
        text = {
            Column {
                DATE_FORMATS.forEach { fmt ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(fmt) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = fmt == current, onClick = { onPick(fmt) })
                        Text(dateFormatLabel(fmt), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
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
                    var expanded by rememberSaveable(d.id) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().cardSurface()) {
                        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name + if (d.current) " · " + stringResource(R.string.device_this) else "", style = MaterialTheme.typography.bodyLarge)
                                if (d.meta.isNotBlank()) Text(d.meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!d.current) {
                                IconButton(onClick = { vm.wipeDevice(d.id) }) { Icon(Icons.Outlined.DeleteForever, stringResource(R.string.device_wipe), tint = MaterialTheme.colorScheme.error) }
                                IconButton(onClick = { vm.revokeDevice(d.id) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.device_revoke)) }
                            }
                        }
                        if (expanded) Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            DeviceDetailRow(stringResource(R.string.device_os), d.osVersion)
                            DeviceDetailRow(stringResource(R.string.device_app_version), d.appVersion)
                            DeviceDetailRow(stringResource(R.string.device_ip), d.ip)
                            DeviceDetailRow(stringResource(R.string.device_last_used), d.lastUsedAt?.take(19)?.replace('T', ' '))
                            DeviceDetailRow(stringResource(R.string.device_connected), d.createdAt?.take(19)?.replace('T', ' '))
                            DeviceDetailRow(stringResource(R.string.device_expires), d.expiresAt?.take(19)?.replace('T', ' '))
                        }
                    }
                }
            }
        }
    }
}

/** One non-secret device detail line; hidden when the value is absent. */
@Composable
private fun DeviceDetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
    }
}

/** Push categories shown as per-category mute toggles (must match server `category` keys). */
private val PUSH_CATEGORIES = listOf(
    "invoice" to R.string.push_cat_invoice,
    "task" to R.string.push_cat_task,
    "event" to R.string.push_cat_event,
    "birthday" to R.string.push_cat_birthday,
    "backup" to R.string.push_cat_backup,
    "system" to R.string.push_cat_system,
)

@Composable
private fun NotificationsScreen(vm: AccountViewModel, onBack: () -> Unit) {
    val items by vm.notifications.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadNotifications() }
    AppScaffold(topBar = {
        AppTopBar(title = stringResource(R.string.settings_notifications), onBack = onBack, actions = {
            TextButton(onClick = { vm.markAllRead() }) { Text(stringResource(R.string.notifications_mark_all)) }
        })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { PushSettingsBlock(vm) }
            item { SectionLabel(stringResource(R.string.notifications_recent)) }
            if (items.isEmpty()) item { EmptyState(stringResource(R.string.notifications_empty)) }
            else items(items, key = { it.id }) { n ->
                Column(Modifier.fillMaxWidth().clickable { if (!n.read) vm.markRead(n.id) }.cardSurface(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(n.title, style = MaterialTheme.typography.bodyLarge, color = if (n.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    n.body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun PushSettingsBlock(vm: AccountViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val enabled by vm.pushEnabled.collectAsStateWithLifecycle()
    val lockContent by vm.pushLockscreenContent.collectAsStateWithLifecycle()
    val muted by vm.pushMutedCategories.collectAsStateWithLifecycle()
    val distributor by vm.pushDistributor.collectAsStateWithLifecycle()
    val hasDistributor = remember { vm.hasDistributor() }

    // POST_NOTIFICATIONS gate: request on enable, then start UnifiedPush registration.
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) (ctx as? android.app.Activity)?.let { act -> vm.enablePush(act) {} }
    }

    fun toggle(on: Boolean) {
        if (on) {
            val needsPerm = android.content.pm.PackageManager.PERMISSION_GRANTED !=
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            if (needsPerm) permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else (ctx as? android.app.Activity)?.let { act -> vm.enablePush(act) {} }
        } else {
            vm.disablePush()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.push_title))
        if (!hasDistributor) {
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.push_no_distributor_title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.push_no_distributor_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            ListSectionCard {
                SwitchRow(stringResource(R.string.push_enable), enabled) { toggle(it) }
                if (enabled) {
                    RowDivider()
                    SwitchRow(stringResource(R.string.push_lockscreen_content), lockContent) { vm.setPushLockscreenContent(it) }
                }
            }
            if (enabled) {
                distributor?.let {
                    Text(stringResource(R.string.push_via, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SectionLabel(stringResource(R.string.push_categories))
                ListSectionCard {
                    PUSH_CATEGORIES.forEachIndexed { i, (key, labelRes) ->
                        if (i > 0) RowDivider()
                        SwitchRow(stringResource(labelRes), !muted.contains(key)) { on -> vm.setCategoryMuted(key, !on) }
                    }
                }
            }
        }
    }
}

/** A labelled row with a trailing Material3 Switch. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
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
    var stepUpPw by remember { mutableStateOf("") } // login password for 2FA/recovery step-up (v1.562.0)
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
                vm.changePassword(curPw, newPw) { err ->
                    msg = when {
                        err == null -> { curPw = ""; newPw = ""; ctx.getString(R.string.security_password_changed) }
                        err.isNotBlank() -> err // server policy / validation message
                        else -> ctx.getString(R.string.security_failed)
                    }
                }
            }) { Text(stringResource(R.string.security_change_password)) }

            SectionLabel(stringResource(R.string.security_2fa))
            // v1.562.0 max-security: enable / recovery-codes / regenerate / disable require the login
            // password as step-up. One shared field feeds all four actions.
            Field(stepUpPw, { stepUpPw = it }, R.string.security_step_up_pw)
            if (twoFa == null) {
                TextButton(enabled = stepUpPw.isNotBlank(), onClick = { scope.launch { twoFa = vm.twoFactorBegin(stepUpPw); if (twoFa == null) msg = ctx.getString(R.string.security_failed) } }) { Text(stringResource(R.string.security_2fa_enable)) }
                TextButton(enabled = stepUpPw.isNotBlank(), onClick = { vm.twoFactorDisable(stepUpPw) { msg = ctx.getString(if (it) R.string.security_2fa_disabled else R.string.security_failed) } }) {
                    Text(stringResource(R.string.security_2fa_disable))
                }
            } else {
                twoFa?.secret?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.security_2fa_secret) + " " + it, style = MaterialTheme.typography.bodyMedium) }
                Field(twoFaCode, { twoFaCode = it }, R.string.security_2fa_code)
                TextButton(enabled = twoFaCode.length >= 6, onClick = {
                    vm.twoFactorConfirm(twoFaCode) { ok -> msg = ctx.getString(if (ok) R.string.security_2fa_enabled else R.string.security_failed); if (ok) { twoFa = null; twoFaCode = ""; stepUpPw = "" } }
                }) { Text(stringResource(R.string.security_2fa_confirm)) }
            }

            SectionLabel(stringResource(R.string.security_recovery_codes))
            if (recoveryCodes.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    recoveryCodes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = stepUpPw.isNotBlank(), onClick = { scope.launch { recoveryCodes = vm.recoveryCodes(stepUpPw); if (recoveryCodes.isEmpty()) msg = ctx.getString(R.string.security_failed) } }) { Text(stringResource(R.string.security_recovery_show)) }
                TextButton(enabled = stepUpPw.isNotBlank(), onClick = { scope.launch { recoveryCodes = vm.regenerateRecoveryCodes(stepUpPw); if (recoveryCodes.isEmpty()) msg = ctx.getString(R.string.security_failed) } }) { Text(stringResource(R.string.security_recovery_regenerate)) }
            }

            // WebDAV mount password
            SectionLabel(stringResource(R.string.sec_webdav))
            var webdavPw by remember { mutableStateOf("") }
            var webdavEnabled by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { webdavEnabled = vm.webdav()?.enabled == true }
            Field(webdavPw, { webdavPw = it }, R.string.sec_webdav_pw)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = webdavPw.length >= 12, onClick = { vm.setWebdav(webdavPw) { ok -> if (ok) { webdavEnabled = true; webdavPw = "" } } }) { Text(stringResource(R.string.sec_webdav_set)) }
                if (webdavEnabled) TextButton(onClick = { vm.clearWebdav { ok -> if (ok) webdavEnabled = false } }) { Text(stringResource(R.string.sec_webdav_clear), color = MaterialTheme.colorScheme.error) }
            }

            // Browser sessions
            var sessions by remember { mutableStateOf<List<de.ledgerline.app.data.remote.dto.SessionRow>>(emptyList()) }
            var sessReload by remember { mutableStateOf(0) }
            LaunchedEffect(sessReload) { sessions = vm.sessions() }
            if (sessions.isNotEmpty()) {
                SectionLabel(stringResource(R.string.sec_sessions))
                sessions.forEach { srow ->
                    Row(Modifier.fillMaxWidth().cardSurface(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(srow.ip ?: "—", style = MaterialTheme.typography.bodyMedium)
                            srow.userAgent?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        }
                        if (!srow.current) TextButton(onClick = { vm.revokeSession(srow.id) { sessReload++ } }) { Text(stringResource(R.string.device_revoke)) }
                    }
                }
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

@Composable
private fun PaperlessScreen(vm: AccountViewModel, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.paperlessConfig()?.let { enabled = it.enabled; url = it.url ?: ""; hasToken = it.hasToken }
    }
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.settings_paperless), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Row(Modifier.fillMaxWidth().cardSurface(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.paperless_enabled), Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Field(url, { url = it }, R.string.paperless_url)
            Field(token, { token = it }, if (hasToken) R.string.admin_secret_keep else R.string.paperless_token)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.savePaperless(enabled, url, token.ifBlank { null }) { ok -> msg = if (ok) "OK" else ctx.getString(R.string.admin_failed); if (ok) { token = ""; hasToken = hasToken || token.isNotBlank() } } }) { Text(stringResource(R.string.action_save)) }
                TextButton(onClick = { vm.testPaperless { ok -> msg = if (ok) "OK" else ctx.getString(R.string.admin_failed) } }) { Text(stringResource(R.string.paperless_test)) }
                TextButton(onClick = { vm.paperlessSync { ok -> msg = if (ok) "OK" else ctx.getString(R.string.admin_failed) } }) { Text(stringResource(R.string.paperless_sync)) }
            }
        }
    }
}
