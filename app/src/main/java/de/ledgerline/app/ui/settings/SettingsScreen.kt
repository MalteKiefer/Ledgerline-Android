package de.ledgerline.app.ui.settings

import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.em
import de.ledgerline.app.ui.theme.IconChip
import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.BuildConfig
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.cardSurface
import de.ledgerline.app.R
import de.ledgerline.app.data.ContactSort
import de.ledgerline.app.data.DateFormatPref
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.backup.DeviceAlbum
import de.ledgerline.app.data.offline.ContactBlobPolicy
import de.ledgerline.app.data.offline.FileBlobPolicy
import de.ledgerline.app.data.offline.PhotoBlobPolicy
import de.ledgerline.app.data.remote.dto.MeUser
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.SectionHeader
import de.ledgerline.app.ui.common.openUrl
import de.ledgerline.app.ui.ops.OpProgressOverlay
import de.ledgerline.app.ui.workspace.common.humanSize
import kotlinx.coroutines.launch

/** Internal Settings destinations — a categorized landing (ROOT) plus one sub-screen per category. */
private enum class SettingsRoute { ROOT, APPEARANCE, SECURITY, MAPS, OFFLINE_MAPS, OFFLINE, BACKGROUND, BACKUP, CALENDAR, ACCOUNT, NOTIFICATIONS, SHARED_LINK, SHARED_VAULTS, ABOUT, LICENSES }

/**
 * Settings screen — a categorized landing list plus per-category sub-screens, in the
 * style of Android system settings. Owns its own [AppTopBar] and internal navigation
 * (via a [rememberSaveable] [SettingsRoute]); no Navigation-Compose is used.
 *
 * Top-level back: if a sub-screen is open, return to the landing list; if already on the
 * landing list, invoke [onBack] to exit Settings entirely (the caller closes the overflow).
 *
 * Mounted from [de.ledgerline.app.ui.workspace.WorkspaceScaffold].
 */
@Composable
fun SettingsContent(
    onLockNow: () -> Unit,
    onDisconnected: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Collect every flow the sub-screens need here in the parent, then pass state +
    // callbacks down. Wiring to SettingsViewModel is unchanged — only the layout moves.
    val timeout by vm.timeoutMinutes.collectAsStateWithLifecycle()
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val keepScreenOnMinutes by vm.keepScreenOnMinutes.collectAsStateWithLifecycle()
    val rememberVault by vm.rememberVaultEnabled.collectAsStateWithLifecycle()
    val rememberVaultDays by vm.rememberVaultDays.collectAsStateWithLifecycle()
    val duressThreshold by vm.duressThreshold.collectAsStateWithLifecycle()
    val securityEvents by vm.securityEvents.collectAsStateWithLifecycle()
    val integrityReport by vm.integrityReport.collectAsStateWithLifecycle()
    val mapTiles by vm.mapTilesEnabled.collectAsStateWithLifecycle()
    val terrain by vm.terrainEnabled.collectAsStateWithLifecycle()
    val backgroundOps by vm.backgroundOpsEnabled.collectAsStateWithLifecycle()
    val linkChooser by vm.linkChooserEnabled.collectAsStateWithLifecycle()
    val refreshSeconds by vm.backgroundRefreshSeconds.collectAsStateWithLifecycle()
    val offlineEnabled by vm.offlineEnabled.collectAsStateWithLifecycle()
    val filesPolicy by vm.filesPolicy.collectAsStateWithLifecycle()
    val photosPolicy by vm.photosPolicy.collectAsStateWithLifecycle()
    val contactsPolicy by vm.contactsPolicy.collectAsStateWithLifecycle()
    val cacheMaxMb by vm.cacheMaxMb.collectAsStateWithLifecycle()
    val prefetchWifiOnly by vm.prefetchWifiOnly.collectAsStateWithLifecycle()
    val prefetchChargingOnly by vm.prefetchChargingOnly.collectAsStateWithLifecycle()
    val prefetchMessage by vm.prefetchMessage.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSizeBytes.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val avatar by vm.avatar.collectAsStateWithLifecycle()
    val contactSort by vm.contactSort.collectAsStateWithLifecycle()
    val contactNameOrder by vm.contactNameOrder.collectAsStateWithLifecycle()
    val dateFormat by vm.dateFormat.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val displayPrefs by vm.displayPrefs.collectAsStateWithLifecycle()
    val coordinateFormat by vm.coordinateFormat.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val userSettings by vm.userSettings.collectAsStateWithLifecycle()
    val backupEnabled by vm.backupEnabled.collectAsStateWithLifecycle()
    val backupAlbumIds by vm.backupAlbumIds.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val backedUpCount by vm.backedUpCount.collectAsStateWithLifecycle()
    val backupDeleteAfter by vm.backupDeleteAfter.collectAsStateWithLifecycle()
    val pendingDeleteUris by vm.pendingDeleteUris.collectAsStateWithLifecycle()

    var route by rememberSaveable { mutableStateOf(SettingsRoute.ROOT) }
    var currentLang by remember { mutableStateOf(currentLanguageTag(context)) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val constraintsMsg = stringResource(R.string.settings_prefetch_constraints)

    // Compute the cache-size line once when the Settings screen is shown.
    LaunchedEffect(Unit) { vm.refreshCacheSize() }

    // Surface the manual-prefetch "constraints not met" reason as a snackbar, once.
    LaunchedEffect(prefetchMessage) {
        if (prefetchMessage == "constraints") {
            snackbarHostState.showSnackbar(constraintsMsg)
            vm.clearPrefetchMessage()
        }
    }

    // Result is ignored: if the user denies notifications the op still runs; the
    // platform simply suppresses the foreground-service notification.
    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Result is ignored: backup runs regardless; the permission grant allows reading media.
    val mediaLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    // "Delete originals after backup": the scoped-storage trash request needs OS consent (the
    // app doesn't own the camera roll). On approval, drop those URIs from the pending queue.
    var trashRequested by remember { mutableStateOf<List<String>>(emptyList()) }
    val trashLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            if (res.resultCode == android.app.Activity.RESULT_OK) vm.onOriginalsDeleted(trashRequested)
            trashRequested = emptyList()
        }
    val requestTrash: () -> Unit = req@{
        val uris = pendingDeleteUris.mapNotNull { runCatching { android.net.Uri.parse(it) }.getOrNull() }
        if (uris.isEmpty()) return@req
        trashRequested = pendingDeleteUris.toList()
        val pi = android.provider.MediaStore.createTrashRequest(context.contentResolver, uris, true)
        trashLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
    }

    // The offline-map manager owns its own full-screen scaffold; render it directly.
    if (route == SettingsRoute.OFFLINE_MAPS) {
        de.ledgerline.app.ui.explore.OfflineMapsScreen(onBack = { route = SettingsRoute.MAPS })
        return
    }

    // Back: a sub-screen returns to the landing list; the landing list exits Settings.
    BackHandler {
        if (route == SettingsRoute.ROOT) onBack() else route = SettingsRoute.ROOT
    }

    val title = when (route) {
        SettingsRoute.ROOT -> stringResource(R.string.settings_title)
        SettingsRoute.APPEARANCE -> stringResource(R.string.settings_cat_appearance)
        SettingsRoute.SECURITY -> stringResource(R.string.settings_cat_security)
        SettingsRoute.MAPS -> stringResource(R.string.settings_cat_maps)
        SettingsRoute.OFFLINE_MAPS -> stringResource(R.string.offline_maps_title)
        SettingsRoute.OFFLINE -> stringResource(R.string.settings_cat_offline)
        SettingsRoute.BACKGROUND -> stringResource(R.string.settings_cat_background)
        SettingsRoute.BACKUP -> stringResource(R.string.settings_cat_backup)
        SettingsRoute.CALENDAR -> stringResource(R.string.settings_cat_calendar)
        SettingsRoute.ACCOUNT -> stringResource(R.string.settings_cat_account)
        SettingsRoute.NOTIFICATIONS -> stringResource(R.string.settings_cat_notifications)
        SettingsRoute.SHARED_LINK -> stringResource(R.string.share_open_title)
        SettingsRoute.SHARED_VAULTS -> stringResource(R.string.vaults_title)
        SettingsRoute.ABOUT -> stringResource(R.string.settings_cat_about)
        SettingsRoute.LICENSES -> stringResource(R.string.settings_licenses)
    }

    AppScaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = title,
                onBack = { if (route == SettingsRoute.ROOT) onBack() else route = SettingsRoute.ROOT },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            when (route) {
                SettingsRoute.ROOT -> SettingsRoot(innerPadding, vm) { route = it }

                SettingsRoute.APPEARANCE -> AppearanceSettings(
                    padding = innerPadding,
                    currentLang = currentLang,
                    onSelectLang = { tag -> applyLanguage(context, tag); currentLang = tag; vm.pushLocale(tag) },
                    contactSort = contactSort,
                    onSelectContactSort = vm::setContactSort,
                    contactNameOrder = contactNameOrder,
                    onSelectNameOrder = vm::setContactNameOrder,
                    dateFormat = dateFormat,
                    onSelectDateFormat = vm::setDateFormat,
                    themeMode = themeMode,
                    onSelectTheme = vm::setThemeMode,
                    prefs = displayPrefs,
                    onSetPrefs = vm::setDisplayPrefs,
                    coordinateFormat = coordinateFormat,
                    onSelectCoord = vm::setCoordinateFormat,
                )

                SettingsRoute.SECURITY -> SecuritySettings(
                    padding = innerPadding,
                    timeout = timeout,
                    onSetTimeout = vm::setTimeoutMinutes,
                    keepScreenOn = keepScreenOn,
                    keepScreenOnMinutes = keepScreenOnMinutes,
                    onSetKeepScreenOn = vm::setKeepScreenOn,
                    onSetKeepScreenOnMinutes = vm::setKeepScreenOnMinutes,
                    rememberVault = rememberVault,
                    rememberVaultDays = rememberVaultDays,
                    biometricAvailable = vm.strongBiometricAvailable,
                    onSetRememberVault = vm::setRememberVaultEnabled,
                    onSetRememberVaultDays = vm::setRememberVaultDays,
                    onLockNow = onLockNow,
                    duressThreshold = duressThreshold,
                    onSetDuressThreshold = vm::setDuressThreshold,
                    securityEvents = securityEvents,
                    onClearSecurityLog = vm::clearSecurityLog,
                    integrity = integrityReport,
                )

                SettingsRoute.MAPS -> MapsSettings(
                    padding = innerPadding,
                    mapTiles = mapTiles,
                    onSetMapTiles = vm::setMapTilesEnabled,
                    terrain = terrain,
                    onSetTerrain = vm::setTerrainEnabled,
                    onOpenOfflineMaps = { route = SettingsRoute.OFFLINE_MAPS },
                )

                SettingsRoute.OFFLINE_MAPS -> Unit // handled by the early return above

                SettingsRoute.OFFLINE -> OfflineSettings(
                    padding = innerPadding,
                    offlineEnabled = offlineEnabled,
                    filesPolicy = filesPolicy,
                    photosPolicy = photosPolicy,
                    contactsPolicy = contactsPolicy,
                    cacheMaxMb = cacheMaxMb,
                    prefetchWifiOnly = prefetchWifiOnly,
                    prefetchChargingOnly = prefetchChargingOnly,
                    cacheSize = cacheSize,
                    onSetOffline = vm::setOfflineEnabled,
                    onSetFilesPolicy = vm::setFilesPolicy,
                    onSetPhotosPolicy = vm::setPhotosPolicy,
                    onSetContactsPolicy = vm::setContactsPolicy,
                    onSetCacheMaxMb = vm::setCacheMaxMb,
                    onSetWifiOnly = vm::setPrefetchWifiOnly,
                    onSetChargingOnly = vm::setPrefetchChargingOnly,
                    onPrefetchNow = vm::prefetchNow,
                    onClearCache = { showClearCacheConfirm = true },
                )

                SettingsRoute.BACKGROUND -> BackgroundSettings(
                    padding = innerPadding,
                    backgroundOps = backgroundOps,
                    linkChooser = linkChooser,
                    refreshSeconds = refreshSeconds,
                    onSetRefreshSeconds = vm::setBackgroundRefreshSeconds,
                    onSetBackgroundOps = { enabled ->
                        vm.setBackgroundOpsEnabled(enabled)
                        // Ask for POST_NOTIFICATIONS on enable (Android 13+) so the ongoing
                        // foreground-service notification is visible while ops run.
                        if (enabled &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onSetLinkChooser = vm::setLinkChooserEnabled,
                )

                SettingsRoute.BACKUP -> BackupSettings(
                    padding = innerPadding,
                    enabled = backupEnabled,
                    albums = albums,
                    selected = backupAlbumIds,
                    backedUpCount = backedUpCount,
                    onSetEnabled = { on ->
                        vm.setBackupEnabled(on)
                        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            mediaLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
                        }
                    },
                    onToggleAlbum = vm::toggleAlbum,
                    onBackupNow = vm::backupNow,
                    onLoadAlbums = vm::loadAlbums,
                    deleteAfter = backupDeleteAfter,
                    onSetDeleteAfter = vm::setBackupDeleteAfter,
                    pendingDeleteCount = pendingDeleteUris.size,
                    onDeletePending = requestTrash,
                )

                SettingsRoute.ACCOUNT -> AccountSettings(
                    padding = innerPadding,
                    account = account,
                    avatar = avatar,
                    devices = devices,
                    userSettings = userSettings,
                    onLoadDevices = vm::loadDevices,
                    onLoadSettings = vm::loadSettings,
                    onRevokeDevice = vm::revokeDevice,
                    onWipeDevice = vm::wipeDevice,
                    onToggleBirthdayChannel = vm::toggleBirthdayChannel,
                    onToggleAnniversaryChannel = vm::toggleAnniversaryChannel,
                    onSetFileMaxVersions = vm::setFileMaxVersions,
                    onDisconnect = { showDisconnectConfirm = true },
                    vm = vm,
                )

                SettingsRoute.CALENDAR -> de.ledgerline.app.ui.calendar.CalendarSettingsContent(innerPadding)
                SettingsRoute.NOTIFICATIONS -> NotificationsSettings(innerPadding)
                SettingsRoute.SHARED_LINK -> de.ledgerline.app.ui.share.SharedLinkContent(innerPadding)
                SettingsRoute.SHARED_VAULTS -> de.ledgerline.app.ui.share.SharedVaultsContent(innerPadding)
                SettingsRoute.ABOUT -> AboutSettings(innerPadding, onOpenLicenses = { route = SettingsRoute.LICENSES })
                SettingsRoute.LICENSES -> LicensesScreen(innerPadding)
            }

            // Shared op overlay: a manual "Prefetch now" (OpKind.PREFETCH) shows here.
            OpProgressOverlay()
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(stringResource(R.string.settings_disconnect)) },
            text = { Text(stringResource(R.string.settings_disconnect_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    scope.launch { vm.disconnect(); onDisconnected() }
                }) { Text(stringResource(R.string.settings_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text(stringResource(R.string.settings_offline_clear)) },
            text = { Text(stringResource(R.string.settings_offline_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheConfirm = false
                    vm.clearCache()
                }) { Text(stringResource(R.string.settings_offline_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/* ----------------------------- Landing list ----------------------------- */

@Composable
private fun SettingsRoot(padding: PaddingValues, vm: SettingsViewModel, onNavigate: (SettingsRoute) -> Unit) {
    val context = LocalContext.current
    val account by vm.account.collectAsStateWithLifecycle()
    val avatar by vm.avatar.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()

    val themeValue = when (themeMode) {
        de.ledgerline.app.data.ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
        de.ledgerline.app.data.ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
        else -> stringResource(R.string.settings_theme_system)
    }
    val langTag = remember { currentLanguageTag(context) }
    val langValue = SUPPORTED_LANGUAGES.firstOrNull { it.first == langTag }?.second
        ?: stringResource(R.string.settings_language_system)

    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()

    // Every root destination as a flat descriptor so the search field can filter across groups.
    data class Item(val group: Int, val title: String, val sub: String, val icon: ImageVector, val tint: Color, val onClick: () -> Unit)
    val g = intArrayOf(0) // group ordinals: 0 Security, 1 Data, 2 Sharing, 3 Appearance, 4 Help
    val items = listOf(
        Item(0, stringResource(R.string.settings_cat_security), stringResource(R.string.settings_cat_security_sub), Icons.Outlined.Lock, Brand.tintViolet) { onNavigate(SettingsRoute.SECURITY) },
        Item(0, stringResource(R.string.settings_cat_autofill), stringResource(R.string.settings_cat_autofill_sub), Icons.Outlined.Password, Brand.tintTeal) { launchAutofillSettings(context) },
        Item(0, stringResource(R.string.settings_cat_passkeys), stringResource(R.string.settings_cat_passkeys_sub), Icons.Outlined.Fingerprint, Brand.tintBlue) { launchCredentialProviderSettings(context) },
        Item(1, stringResource(R.string.settings_cat_offline), stringResource(R.string.settings_cat_offline_sub), Icons.Outlined.CloudOff, Brand.tintGreen) { onNavigate(SettingsRoute.OFFLINE) },
        Item(1, stringResource(R.string.settings_cat_background), stringResource(R.string.settings_cat_background_sub), Icons.Outlined.Sync, Brand.tintTeal) { onNavigate(SettingsRoute.BACKGROUND) },
        Item(1, stringResource(R.string.settings_cat_backup), stringResource(R.string.settings_cat_backup_sub), Icons.Outlined.PhotoLibrary, Brand.tintOrange) { onNavigate(SettingsRoute.BACKUP) },
        Item(1, stringResource(R.string.settings_cat_maps), stringResource(R.string.settings_cat_maps_sub), Icons.Outlined.Map, Brand.tintTeal) { onNavigate(SettingsRoute.MAPS) },
        Item(1, stringResource(R.string.settings_cat_calendar), stringResource(R.string.settings_cat_calendar_sub), Icons.Outlined.CalendarMonth, Brand.tintOrange) { onNavigate(SettingsRoute.CALENDAR) },
        Item(2, stringResource(R.string.share_open_title), stringResource(R.string.share_open_hint), Icons.Outlined.Link, Brand.tintBlue) { onNavigate(SettingsRoute.SHARED_LINK) },
        Item(2, stringResource(R.string.vaults_title), stringResource(R.string.settings_cat_vaults_sub), Icons.Outlined.Share, Brand.tintViolet) { onNavigate(SettingsRoute.SHARED_VAULTS) },
        Item(3, stringResource(R.string.settings_theme), themeValue, Icons.Outlined.Contrast, Brand.tintViolet) { onNavigate(SettingsRoute.APPEARANCE) },
        Item(3, stringResource(R.string.settings_language), langValue, Icons.Outlined.Language, Brand.tintBlue) { onNavigate(SettingsRoute.APPEARANCE) },
        Item(4, stringResource(R.string.settings_cat_notifications), stringResource(R.string.settings_cat_notifications_sub), Icons.Outlined.Notifications, Brand.tintOrange) { onNavigate(SettingsRoute.NOTIFICATIONS) },
        Item(4, stringResource(R.string.settings_cat_about), stringResource(R.string.settings_cat_about_sub), Icons.Outlined.Info, Brand.tintGray) { onNavigate(SettingsRoute.ABOUT) },
    )
    val groupLabels = listOf(
        R.string.settings_group_security, R.string.settings_group_data,
        R.string.settings_group_sharing, R.string.settings_cat_appearance, R.string.settings_group_help,
    )
    val shown = if (q.isEmpty()) items else items.filter { it.title.lowercase().contains(q) || it.sub.lowercase().contains(q) }

    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Search
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.settings_search)) },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Account header card (hidden while searching to keep results tight)
        if (q.isEmpty()) item {
            AccountHeaderCard(account, avatar, storage, vm.serverHost, onClick = { onNavigate(SettingsRoute.ACCOUNT) })
        }
        // Grouped rows
        for (gi in groupLabels.indices) {
            val rows = shown.filter { it.group == gi }
            if (rows.isEmpty()) continue
            item(key = "label$gi") { SettingsSectionLabel(stringResource(groupLabels[gi])) }
            item(key = "group$gi") {
                SettingsGroup {
                    rows.forEachIndexed { i, it ->
                        if (i > 0) SettingsRowDivider()
                        SettingsRow(it.icon, it.tint, it.title, it.sub, it.onClick)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** Small uppercase group label above a settings card (M3/OneUI settings pattern). */
@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Brand.accent,
        letterSpacing = 0.08.em,
        modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** A rounded elevated container that visually groups a set of [SettingsRow]s. */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().cardSurface(),
        content = content,
    )
}

/**
 * One labeled settings section: an uppercase [label] over a rounded [SettingsGroup] card, inset
 * from the screen edge. The building block every detail sub-screen uses so the whole Settings
 * area reads as one system (matching the redesigned home). [danger] tints the label red for a
 * destructive group.
 */
@Composable
private fun SettingsSection(label: String, danger: Boolean = false, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = if (danger) MaterialTheme.colorScheme.error else Brand.accent,
        letterSpacing = 0.08.em,
        modifier = Modifier.padding(start = 22.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
    if (hint != null) Text(
        hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp),
    )
    Column(Modifier.padding(horizontal = 16.dp)) { SettingsGroup(content = content) }
}

@Composable
private fun SettingsRowDivider() {
    androidx.compose.material3.HorizontalDivider(
        Modifier.padding(start = 60.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/** One settings row: tinted icon chip, title, current value/summary, trailing chevron. */
@Composable
private fun SettingsRow(icon: ImageVector, tint: Color, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(icon, tint = tint, size = 32.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value.isNotBlank()) Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A settings row with a tinted chip + title + optional value and a trailing action slot. */
@Composable
private fun AccountActionRow(icon: ImageVector, tint: Color, title: String, value: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(icon, tint = tint, size = 32.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Identity-first header: avatar, name, email, connected server, account-wide storage ring. */
@Composable
private fun AccountHeaderCard(
    account: MeUser?,
    avatar: androidx.compose.ui.graphics.ImageBitmap?,
    storage: de.ledgerline.app.data.AccountRepository.AccountSnapshot?,
    serverHost: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().cardSurface().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar: image if present, else gradient initials.
        if (avatar != null) {
            androidx.compose.foundation.Image(
                bitmap = avatar, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape),
            )
        } else {
            Box(
                Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Brand.accentGradient),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initials(account?.name ?: account?.email),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(account?.name ?: account?.email ?: stringResource(R.string.account_loading), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            account?.email?.takeIf { it != account.name }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            serverHost?.let {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = Brand.accent, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Brand.accent, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        storage?.let { StorageRing(it) }
    }
}

/** A compact circular usage gauge (files+gallery vs quota); shows "—" when unlimited. */
@Composable
private fun StorageRing(s: de.ledgerline.app.data.AccountRepository.AccountSnapshot) {
    val quota = s.quotaBytes
    val frac = if (quota != null && quota > 0) (s.usedBytes.toFloat() / quota).coerceIn(0f, 1f) else 0f
    val accent = Brand.accent
    val track = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(44.dp)) {
            val sw = 4.dp.toPx()
            drawArc(color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = Stroke(width = sw))
            if (quota != null) drawArc(
                color = accent, startAngle = -90f, sweepAngle = 360f * frac, useCenter = false,
                style = Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        Text(
            if (quota == null) "—" else "${(frac * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Up to two uppercase initials from a name/email for the avatar fallback. */
private fun initials(s: String?): String {
    val base = s?.trim().orEmpty()
    if (base.isEmpty()) return "?"
    val parts = base.substringBefore('@').split(' ', '.', '-').filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
        else -> base.take(2).uppercase()
    }
}

/**
 * Opens the system dialog to set Ledgerline as the device autofill service. Uses
 * ACTION_REQUEST_SET_AUTOFILL_SERVICE targeted at our package when autofill is supported and we
 * are not already the provider; otherwise falls back to the general autofill settings so the user
 * can switch away. Best-effort — swallows the (rare) ActivityNotFound on stripped-down ROMs.
 */
private fun launchAutofillSettings(context: Context) {
    val am = context.getSystemService(AutofillManager::class.java)
    val intent = if (am != null && am.isAutofillSupported && !am.hasEnabledAutofillServices()) {
        Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
            .setData(Uri.parse("package:${context.packageName}"))
    } else {
        Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
    }
    runCatching { context.startActivity(intent) }
}

/**
 * Opens the system Credential Manager settings so the user can enable Ledgerline as a passkey /
 * credential provider (Android 14+). Targets our package where supported; falls back to the
 * general screen. Best-effort — swallows ActivityNotFound on ROMs without the setting.
 */
private fun launchCredentialProviderSettings(context: Context) {
    val direct = Intent("android.settings.CREDENTIAL_PROVIDER")
        .setData(Uri.parse("package:${context.packageName}"))
    val fallback = Intent(Settings.ACTION_SYNC_SETTINGS)
    runCatching { context.startActivity(direct) }
        .recoverCatching { context.startActivity(Intent("android.settings.CREDENTIAL_PROVIDER")) }
        .recoverCatching { context.startActivity(fallback) }
}

/** Legacy tonal category row (still used inside detail screens like Maps → offline maps). */
@Composable
private fun CategoryRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
    )
}

/* --------------------------- Category sub-screens --------------------------- */

/**
 * App languages offered in Settings, as (BCP-47 tag → native display name). Kept in
 * sync with res/xml/locales_config.xml and the values-<tag>/strings.xml translations.
 * "System default" is a separate localized row.
 */
private val SUPPORTED_LANGUAGES = listOf(
    "en" to "English",
    "de" to "Deutsch",
    "ru" to "Русский",
)

@Composable
private fun MapsSettings(
    padding: PaddingValues,
    mapTiles: Boolean,
    onSetMapTiles: (Boolean) -> Unit,
    terrain: Boolean,
    onSetTerrain: (Boolean) -> Unit,
    onOpenOfflineMaps: () -> Unit,
) {
    SubScreen(padding) {
        SettingsSection(stringResource(R.string.settings_maps_online)) {
            SwitchRow(stringResource(R.string.settings_maps_tiles), stringResource(R.string.settings_maps_tiles_sub), mapTiles, onSetMapTiles)
            SettingsRowDivider()
            SwitchRow(stringResource(R.string.settings_maps_terrain), stringResource(R.string.settings_maps_terrain_sub), terrain, onSetTerrain)
        }
        SettingsSection(stringResource(R.string.settings_maps_offline)) {
            SettingsRow(androidx.compose.material.icons.Icons.Outlined.Download, Brand.tintTeal, stringResource(R.string.offline_maps_title), stringResource(R.string.settings_maps_offline_sub), onOpenOfflineMaps)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AppearanceSettings(
    padding: PaddingValues,
    currentLang: String,
    onSelectLang: (String) -> Unit,
    contactSort: ContactSort,
    onSelectContactSort: (ContactSort) -> Unit,
    contactNameOrder: de.ledgerline.app.data.ContactNameOrder,
    onSelectNameOrder: (de.ledgerline.app.data.ContactNameOrder) -> Unit,
    dateFormat: DateFormatPref,
    onSelectDateFormat: (DateFormatPref) -> Unit,
    themeMode: de.ledgerline.app.data.ThemeMode,
    onSelectTheme: (de.ledgerline.app.data.ThemeMode) -> Unit,
    prefs: de.ledgerline.app.core.prefs.DisplayPrefs,
    onSetPrefs: (de.ledgerline.app.core.prefs.DisplayPrefs) -> Unit,
    coordinateFormat: de.ledgerline.app.core.geo.CoordinateFormat,
    onSelectCoord: (de.ledgerline.app.core.geo.CoordinateFormat) -> Unit,
) {
    SubScreen(padding) {
        SettingsSection(stringResource(R.string.settings_theme)) {
            RadioRow(stringResource(R.string.settings_theme_system), themeMode == de.ledgerline.app.data.ThemeMode.SYSTEM) { onSelectTheme(de.ledgerline.app.data.ThemeMode.SYSTEM) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.settings_theme_light), themeMode == de.ledgerline.app.data.ThemeMode.LIGHT) { onSelectTheme(de.ledgerline.app.data.ThemeMode.LIGHT) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.settings_theme_dark), themeMode == de.ledgerline.app.data.ThemeMode.DARK) { onSelectTheme(de.ledgerline.app.data.ThemeMode.DARK) }
        }
        SettingsSection(stringResource(R.string.settings_units)) {
            PrefChoiceRow(stringResource(R.string.settings_pref_distance), listOf("km" to "km", "mi" to "mi"), prefs.distance) { onSetPrefs(prefs.copy(distance = it)) }
            PrefChoiceRow(stringResource(R.string.settings_pref_elevation), listOf("m" to "m", "ft" to "ft"), prefs.elevation) { onSetPrefs(prefs.copy(elevation = it)) }
            PrefChoiceRow(stringResource(R.string.health_unit_weight), listOf("kg" to "kg", "lb" to "lb"), prefs.weight) { onSetPrefs(prefs.copy(weight = it)) }
            PrefChoiceRow(stringResource(R.string.health_unit_temp), listOf("c" to "°C", "f" to "°F"), prefs.temp) { onSetPrefs(prefs.copy(temp = it)) }
            PrefChoiceRow(stringResource(R.string.health_unit_glucose), listOf("mgdl" to "mg/dL", "mmoll" to "mmol/L"), prefs.glucose) { onSetPrefs(prefs.copy(glucose = it)) }
        }
        SettingsSection(stringResource(R.string.settings_clock)) {
            PrefChoiceRow(stringResource(R.string.settings_clock), listOf("24h" to "24 h", "12h" to "12 h"), prefs.timeFormat) { onSetPrefs(prefs.copy(timeFormat = it)) }
        }
        SettingsSection(stringResource(R.string.settings_coord_format)) {
            de.ledgerline.app.core.geo.CoordinateFormat.entries.forEachIndexed { i, f ->
                if (i > 0) SettingsRowDivider()
                RadioRow(f.name, coordinateFormat == f) { onSelectCoord(f) }
            }
        }
        SettingsSection(stringResource(R.string.settings_language)) {
            RadioRow(stringResource(R.string.settings_language_system), currentLang == "") { onSelectLang("") }
            SUPPORTED_LANGUAGES.forEach { (tag, nativeName) ->
                SettingsRowDivider()
                RadioRow(nativeName, currentLang == tag) { onSelectLang(tag) }
            }
        }
        SettingsSection(stringResource(R.string.settings_contact_sort)) {
            RadioRow(stringResource(R.string.settings_contact_sort_first), contactSort == ContactSort.FIRST) { onSelectContactSort(ContactSort.FIRST) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.settings_contact_sort_last), contactSort == ContactSort.LAST) { onSelectContactSort(ContactSort.LAST) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.settings_contact_sort_display), contactSort == ContactSort.DISPLAY) { onSelectContactSort(ContactSort.DISPLAY) }
        }
        SettingsSection(stringResource(R.string.settings_contact_name_order)) {
            RadioRow(stringResource(R.string.settings_contact_name_last_first), contactNameOrder == de.ledgerline.app.data.ContactNameOrder.LAST_FIRST) { onSelectNameOrder(de.ledgerline.app.data.ContactNameOrder.LAST_FIRST) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.settings_contact_name_first_last), contactNameOrder == de.ledgerline.app.data.ContactNameOrder.FIRST_LAST) { onSelectNameOrder(de.ledgerline.app.data.ContactNameOrder.FIRST_LAST) }
        }
        SettingsSection(stringResource(R.string.settings_date_format)) {
            RadioRow(stringResource(R.string.settings_date_format_system), dateFormat == DateFormatPref.SYSTEM) { onSelectDateFormat(DateFormatPref.SYSTEM) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.settings_date_format_dmy), dateFormat == DateFormatPref.DMY) { onSelectDateFormat(DateFormatPref.DMY) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.settings_date_format_ymd), dateFormat == DateFormatPref.YMD) { onSelectDateFormat(DateFormatPref.YMD) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.settings_date_format_mdy), dateFormat == DateFormatPref.MDY) { onSelectDateFormat(DateFormatPref.MDY) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SecuritySettings(
    padding: PaddingValues,
    timeout: Int,
    onSetTimeout: (Int) -> Unit,
    keepScreenOn: Boolean,
    keepScreenOnMinutes: Int,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetKeepScreenOnMinutes: (Int) -> Unit,
    rememberVault: Boolean,
    rememberVaultDays: Int,
    biometricAvailable: Boolean,
    onSetRememberVault: (Boolean) -> Unit,
    onSetRememberVaultDays: (Int) -> Unit,
    onLockNow: () -> Unit,
    duressThreshold: Int,
    onSetDuressThreshold: (Int) -> Unit,
    securityEvents: List<de.ledgerline.app.core.security.SecurityLogEntry>,
    onClearSecurityLog: () -> Unit,
    integrity: de.ledgerline.app.core.integrity.IntegrityReport?,
) {
    SubScreen(padding) {
        // Integrity card keeps its own visual; just give it a section label + inset.
        Text(
            stringResource(R.string.settings_integrity_title).uppercase(),
            style = MaterialTheme.typography.labelMedium, color = Brand.accent, letterSpacing = 0.08.em,
            modifier = Modifier.padding(start = 22.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        )
        Column(Modifier.padding(horizontal = 16.dp)) { IntegrityCard(integrity) }

        SettingsSection(stringResource(R.string.settings_idle_timeout)) {
            SettingsStore.TIMEOUT_OPTIONS.forEachIndexed { i, minutes ->
                if (i > 0) SettingsRowDivider()
                RadioRow(timeoutLabel(minutes), timeout == minutes) { onSetTimeout(minutes) }
            }
        }
        SettingsSection(stringResource(R.string.settings_keep_screen_on)) {
            SwitchRow(stringResource(R.string.settings_keep_screen_on), stringResource(R.string.settings_keep_screen_on_note), keepScreenOn, onSetKeepScreenOn)
            if (keepScreenOn) SettingsStore.KEEP_SCREEN_ON_OPTIONS.forEach { minutes ->
                SettingsRowDivider()
                RadioRow(keepScreenOnLabel(minutes), keepScreenOnMinutes == minutes, enabled = keepScreenOn) { onSetKeepScreenOnMinutes(minutes) }
            }
        }
        SettingsSection(stringResource(R.string.settings_remember_vault)) {
            SwitchRow(
                stringResource(R.string.settings_remember_vault),
                stringResource(if (biometricAvailable) R.string.settings_remember_vault_note else R.string.settings_remember_vault_needs_biometric),
                rememberVault && biometricAvailable, onSetRememberVault, enabled = biometricAvailable,
            )
            if (rememberVault && biometricAvailable) SettingsStore.REMEMBER_VAULT_DAYS_OPTIONS.forEach { days ->
                SettingsRowDivider()
                RadioRow(daysLabel(days), rememberVaultDays == days) { onSetRememberVaultDays(days) }
            }
        }
        OutlinedButton(
            onClick = onLockNow,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp),
        ) { Text(stringResource(R.string.settings_lock_now)) }
        Text(
            stringResource(R.string.settings_lock_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
        )

        SettingsSection(stringResource(R.string.security_duress_title), hint = stringResource(R.string.security_duress_body)) {
            de.ledgerline.app.core.security.WipePolicy.options.forEachIndexed { i, n ->
                if (i > 0) SettingsRowDivider()
                RadioRow(stringResource(R.string.security_duress_threshold, n), duressThreshold == n) { onSetDuressThreshold(n) }
            }
        }

        SettingsSection(stringResource(R.string.security_log_title)) {
            if (securityEvents.isEmpty()) {
                Text(
                    stringResource(R.string.security_log_empty),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            } else {
                securityEvents.asReversed().take(100).forEachIndexed { i, e ->
                    if (i > 0) SettingsRowDivider()
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text(securityEventLabel(e.type), style = MaterialTheme.typography.bodyMedium)
                        Text(formatSecTs(e.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (securityEvents.isNotEmpty()) OutlinedButton(
            onClick = onClearSecurityLog,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp),
        ) { Text(stringResource(R.string.security_log_clear)) }
        Spacer(Modifier.height(20.dp))
    }
}

/** Localised label for a [de.ledgerline.app.core.security.SecurityEventType] name. */
@Composable
private fun securityEventLabel(type: String): String = stringResource(
    when (type) {
        "PAIRED" -> R.string.sec_event_PAIRED
        "UNLOCK_SUCCESS" -> R.string.sec_event_UNLOCK_SUCCESS
        "UNLOCK_FAILED" -> R.string.sec_event_UNLOCK_FAILED
        "RECOVERY_UNLOCK" -> R.string.sec_event_RECOVERY_UNLOCK
        "THROTTLE_LOCKOUT" -> R.string.sec_event_THROTTLE_LOCKOUT
        "DURESS_WIPE" -> R.string.sec_event_DURESS_WIPE
        "REMOTE_WIPE" -> R.string.sec_event_REMOTE_WIPE
        "LOGOUT" -> R.string.sec_event_LOGOUT
        else -> R.string.sec_event_UNLOCK_FAILED
    },
)

private fun formatSecTs(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))

@Composable
private fun OfflineSettings(
    padding: PaddingValues,
    offlineEnabled: Boolean,
    filesPolicy: FileBlobPolicy,
    photosPolicy: PhotoBlobPolicy,
    contactsPolicy: ContactBlobPolicy,
    cacheMaxMb: Int,
    prefetchWifiOnly: Boolean,
    prefetchChargingOnly: Boolean,
    cacheSize: Long,
    onSetOffline: (Boolean) -> Unit,
    onSetFilesPolicy: (FileBlobPolicy) -> Unit,
    onSetPhotosPolicy: (PhotoBlobPolicy) -> Unit,
    onSetContactsPolicy: (ContactBlobPolicy) -> Unit,
    onSetCacheMaxMb: (Int) -> Unit,
    onSetWifiOnly: (Boolean) -> Unit,
    onSetChargingOnly: (Boolean) -> Unit,
    onPrefetchNow: () -> Unit,
    onClearCache: () -> Unit,
) {
    SubScreen(padding) {
        SettingsSection(stringResource(R.string.settings_offline_section)) {
            SwitchRow(stringResource(R.string.settings_offline_title), stringResource(R.string.settings_offline_subtitle), offlineEnabled, onSetOffline)
        }
        SettingsSection(stringResource(R.string.settings_files_policy)) {
            RadioRow(stringResource(R.string.policy_off), filesPolicy == FileBlobPolicy.OFF, offlineEnabled) { onSetFilesPolicy(FileBlobPolicy.OFF) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.policy_on_demand), filesPolicy == FileBlobPolicy.ON_DEMAND, offlineEnabled) { onSetFilesPolicy(FileBlobPolicy.ON_DEMAND) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.policy_all), filesPolicy == FileBlobPolicy.ALL, offlineEnabled) { onSetFilesPolicy(FileBlobPolicy.ALL) }
        }
        SettingsSection(stringResource(R.string.settings_photos_policy)) {
            RadioRow(stringResource(R.string.policy_off), photosPolicy == PhotoBlobPolicy.OFF, offlineEnabled) { onSetPhotosPolicy(PhotoBlobPolicy.OFF) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.policy_thumbs), photosPolicy == PhotoBlobPolicy.THUMBS, offlineEnabled) { onSetPhotosPolicy(PhotoBlobPolicy.THUMBS) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.policy_on_demand), photosPolicy == PhotoBlobPolicy.ON_DEMAND, offlineEnabled) { onSetPhotosPolicy(PhotoBlobPolicy.ON_DEMAND) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.policy_all), photosPolicy == PhotoBlobPolicy.ALL, offlineEnabled) { onSetPhotosPolicy(PhotoBlobPolicy.ALL) }
        }
        SettingsSection(stringResource(R.string.settings_contacts_policy), hint = stringResource(R.string.settings_offline_manifest_note)) {
            RadioRow(stringResource(R.string.policy_off), contactsPolicy == ContactBlobPolicy.OFF, offlineEnabled) { onSetContactsPolicy(ContactBlobPolicy.OFF) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.policy_on_demand), contactsPolicy == ContactBlobPolicy.ON_DEMAND, offlineEnabled) { onSetContactsPolicy(ContactBlobPolicy.ON_DEMAND) }
            SettingsRowDivider()
            RadioRow(stringResource(R.string.policy_all), contactsPolicy == ContactBlobPolicy.ALL, offlineEnabled) { onSetContactsPolicy(ContactBlobPolicy.ALL) }
        }
        SettingsSection(stringResource(R.string.settings_cache_limit)) {
            SettingsStore.CACHE_MAX_MB_OPTIONS.forEachIndexed { i, mb ->
                if (i > 0) SettingsRowDivider()
                RadioRow(cacheLimitLabel(mb), cacheMaxMb == mb, offlineEnabled) { onSetCacheMaxMb(mb) }
            }
        }
        SettingsSection(stringResource(R.string.settings_group_data)) {
            SwitchRow(stringResource(R.string.settings_prefetch_wifi), "", prefetchWifiOnly, onSetWifiOnly, enabled = offlineEnabled)
            SettingsRowDivider()
            SwitchRow(stringResource(R.string.settings_prefetch_charging), "", prefetchChargingOnly, onSetChargingOnly, enabled = offlineEnabled)
        }
        OutlinedButton(
            onClick = onPrefetchNow, enabled = offlineEnabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp),
        ) { Text(stringResource(R.string.settings_prefetch_now)) }

        SettingsSection(stringResource(R.string.settings_offline_clear), danger = true, hint = stringResource(R.string.settings_offline_size, humanSize(cacheSize))) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onClearCache).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconChip(Icons.Outlined.Delete, tint = MaterialTheme.colorScheme.error, size = 32.dp)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.settings_offline_clear), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun BackgroundSettings(
    padding: PaddingValues,
    backgroundOps: Boolean,
    linkChooser: Boolean,
    refreshSeconds: Int,
    onSetBackgroundOps: (Boolean) -> Unit,
    onSetLinkChooser: (Boolean) -> Unit,
    onSetRefreshSeconds: (Int) -> Unit,
) {
    SubScreen(padding) {
        SettingsSection(stringResource(R.string.settings_refresh_title), hint = stringResource(R.string.settings_refresh_sub)) {
            SettingsStore.BACKGROUND_REFRESH_OPTIONS.forEachIndexed { i, seconds ->
                if (i > 0) SettingsRowDivider()
                RadioRow(refreshLabel(seconds), refreshSeconds == seconds) { onSetRefreshSeconds(seconds) }
            }
        }
        SettingsSection(stringResource(R.string.settings_group_data)) {
            SwitchRow(stringResource(R.string.settings_background_ops_title), stringResource(R.string.settings_background_ops_subtitle), backgroundOps, onSetBackgroundOps)
            SettingsRowDivider()
            SwitchRow(stringResource(R.string.settings_link_chooser), "", linkChooser, onSetLinkChooser)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun BackupSettings(
    padding: PaddingValues,
    enabled: Boolean,
    albums: List<DeviceAlbum>,
    selected: Set<String>,
    backedUpCount: Int,
    onSetEnabled: (Boolean) -> Unit,
    onToggleAlbum: (String) -> Unit,
    onBackupNow: () -> Unit,
    onLoadAlbums: () -> Unit,
    deleteAfter: Boolean,
    onSetDeleteAfter: (Boolean) -> Unit,
    pendingDeleteCount: Int,
    onDeletePending: () -> Unit,
) {
    LaunchedEffect(enabled) { if (enabled) onLoadAlbums() }
    SubScreen(padding) {
        SettingsSection(stringResource(R.string.settings_backup_section)) {
            SwitchRow(stringResource(R.string.settings_backup_title), stringResource(R.string.settings_backup_subtitle), enabled, onSetEnabled)
        }
        if (enabled) {
            SettingsSection(stringResource(R.string.settings_backup_cleanup)) {
                SwitchRow(
                    stringResource(R.string.settings_backup_delete_title),
                    stringResource(R.string.settings_backup_delete_subtitle),
                    deleteAfter,
                    onSetDeleteAfter,
                )
                if (pendingDeleteCount > 0) {
                    SettingsRowDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { onDeletePending() }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconChip(Icons.Outlined.DeleteSweep, tint = Brand.tintOrange, size = 32.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            stringResource(R.string.settings_backup_delete_pending, pendingDeleteCount),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_backup_albums), hint = stringResource(R.string.settings_backup_status, backedUpCount)) {
                albums.forEachIndexed { i, a ->
                    if (i > 0) SettingsRowDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggleAlbum(a.bucketId) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconChip(Icons.Outlined.PhotoLibrary, tint = Brand.tintOrange, size = 32.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.settings_backup_album_count, a.count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked = a.bucketId in selected, onCheckedChange = { onToggleAlbum(a.bucketId) })
                    }
                }
            }
            OutlinedButton(onClick = onBackupNow, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp)) {
                Text(stringResource(R.string.settings_backup_now))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AccountSettings(
    padding: PaddingValues,
    account: MeUser?,
    avatar: androidx.compose.ui.graphics.ImageBitmap?,
    devices: List<de.ledgerline.app.data.remote.dto.DeviceDto>,
    userSettings: de.ledgerline.app.data.remote.dto.UserSettingsDto?,
    onLoadDevices: () -> Unit,
    onLoadSettings: () -> Unit,
    onRevokeDevice: (Long) -> Unit,
    onWipeDevice: (Long) -> Unit,
    onToggleBirthdayChannel: (String) -> Unit,
    onToggleAnniversaryChannel: (String) -> Unit,
    onSetFileMaxVersions: (Int) -> Unit,
    onDisconnect: () -> Unit,
    vm: SettingsViewModel,
) {
    LaunchedEffect(Unit) { onLoadDevices(); onLoadSettings() }
    SubScreen(padding) {
        SettingsSection(stringResource(R.string.settings_account)) {
            if (account != null) {
                AccountField(stringResource(R.string.account_name), account.name ?: "—")
                SettingsRowDivider()
                AccountField(stringResource(R.string.account_email), account.email ?: "—")
                if (account.groups.isNotEmpty()) {
                    SettingsRowDivider()
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(stringResource(R.string.account_groups), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            account.groups.forEach { g -> AssistChip(onClick = {}, label = { Text(g) }) }
                        }
                    }
                }
            } else {
                Text(stringResource(R.string.account_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp))
            }
        }
        if (devices.isNotEmpty()) SettingsSection(stringResource(R.string.settings_devices)) {
            devices.forEachIndexed { i, d ->
                if (i > 0) SettingsRowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconChip(if (d.current) Icons.Outlined.Smartphone else Icons.Outlined.Devices, tint = if (d.current) Brand.tintGreen else Brand.tintGray, size = 32.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (d.current) stringResource(R.string.settings_device_this, d.name) else d.name, style = MaterialTheme.typography.bodyLarge)
                        val extra = listOfNotNull(d.meta.takeIf { it.isNotBlank() }, d.version).joinToString(" · ")
                        Text(if (d.wipeRequested) stringResource(R.string.settings_device_wipe_pending) else extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!d.current) {
                        if (!d.wipeRequested) IconButton(onClick = { onWipeDevice(d.id) }) { Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.settings_device_wipe)) }
                        IconButton(onClick = { onRevokeDevice(d.id) }) { Icon(Icons.Outlined.Logout, contentDescription = stringResource(R.string.settings_device_revoke)) }
                    }
                }
            }
        }
        userSettings?.let { s ->
            SettingsSection(stringResource(R.string.settings_notify_channels), hint = stringResource(R.string.settings_notify_channels_sub)) {
                ChannelRow(stringResource(R.string.settings_birthday_channels), s.birthdayChannels.orEmpty(), onToggleBirthdayChannel)
                SettingsRowDivider()
                ChannelRow(stringResource(R.string.settings_anniversary_channels), s.anniversaryChannels.orEmpty(), onToggleAnniversaryChannel)
            }
            val current = s.fileMaxVersions ?: 10
            SettingsSection(stringResource(R.string.settings_file_versions)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_file_versions_cap), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.settings_file_versions_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onSetFileMaxVersions(current - 1) }, enabled = current > 1) { Text("−", style = MaterialTheme.typography.titleLarge) }
                    Text("$current", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { onSetFileMaxVersions(current + 1) }, enabled = current < 200) { Text("+", style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
        AccountControlSection(vm, accountEmail = account?.email)
        Button(
            onClick = onDisconnect,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 8.dp),
        ) { Text(stringResource(R.string.settings_disconnect)) }
        Spacer(Modifier.height(16.dp))
    }
}

/** Login 2FA + password change + GDPR export/delete. Orthogonal to the ZK vault. */
@Composable
private fun AccountControlSection(vm: SettingsViewModel, accountEmail: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val setup by vm.twoFactorSetup.collectAsStateWithLifecycle()
    val codes by vm.recoveryCodes.collectAsStateWithLifecycle()
    val tfaMsg by vm.twoFactorMessage.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadRecoveryCodes() }
    LaunchedEffect(tfaMsg) {
        val key = tfaMsg ?: return@LaunchedEffect
        val text = when (key) {
            "2fa_enabled" -> context.getString(R.string.tfa_enabled)
            "2fa_disabled" -> context.getString(R.string.tfa_disabled)
            "2fa_bad_code" -> context.getString(R.string.tfa_bad_code)
            else -> context.getString(R.string.tfa_failed)
        }
        snackbar.showSnackbar(text); vm.clearTwoFactorMessage()
    }

    // --- GDPR export (stream → SAF) ---
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    val exportSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pendingExport; pendingExport = null
        if (uri != null && bytes != null) scope.launch {
            withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } } }
        }
    }

    var showDelete by remember { mutableStateOf(false) }
    var show2faDialog by remember { mutableStateOf(false) }
    var showPwDialog by remember { mutableStateOf(false) }

    SettingsSection(stringResource(R.string.settings_login_security)) {
        if (codes.isNotEmpty()) {
            AccountActionRow(Icons.Outlined.Shield, Brand.tintViolet, stringResource(R.string.tfa_title), stringResource(R.string.tfa_on)) {
                TextButton(onClick = vm::disableTwoFactor) { Text(stringResource(R.string.tfa_disable)) }
            }
            SettingsRowDivider()
            AccountActionRow(Icons.Outlined.VpnKey, Brand.tintTeal, stringResource(R.string.tfa_recovery), codes.joinToString("  ")) {
                TextButton(onClick = vm::regenerateRecoveryCodes) { Text(stringResource(R.string.tfa_regenerate)) }
            }
        } else {
            AccountActionRow(Icons.Outlined.Shield, Brand.tintViolet, stringResource(R.string.tfa_title), stringResource(R.string.tfa_off)) {
                TextButton(onClick = { vm.beginTwoFactor(); show2faDialog = true }) { Text(stringResource(R.string.tfa_enable)) }
            }
        }
        SettingsRowDivider()
        AccountActionRow(Icons.Outlined.Password, Brand.tintBlue, stringResource(R.string.pw_change_title), "") {
            TextButton(onClick = { showPwDialog = true }) { Text(stringResource(R.string.pw_change_action)) }
        }
    }

    SettingsSection(stringResource(R.string.settings_account_data), danger = true) {
        AccountActionRow(Icons.Outlined.Download, Brand.tintGreen, stringResource(R.string.account_export), "") {
            TextButton(onClick = { vm.exportAccount { bytes -> if (bytes != null) { pendingExport = bytes; exportSaver.launch("ledgerline-export.zip") } } }) { Text(stringResource(R.string.settings_export_action)) }
        }
        SettingsRowDivider()
        Row(Modifier.fillMaxWidth().clickable { showDelete = true }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            IconChip(Icons.Outlined.Delete, tint = MaterialTheme.colorScheme.error, size = 32.dp)
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.account_delete), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }
    }

    androidx.compose.material3.SnackbarHost(snackbar)

    if (show2faDialog && setup != null) TwoFactorDialog(setup!!, onConfirm = { vm.confirmTwoFactor(it) }, onDismiss = { show2faDialog = false; vm.cancelTwoFactorSetup() })
    if (showPwDialog) ChangePasswordDialog(onSubmit = { cur, new -> showPwDialog = false; vm.changePassword(cur, new) { ok -> scope.launch { snackbar.showSnackbar(context.getString(if (ok) R.string.pw_change_ok else R.string.pw_change_fail)) } } }, onDismiss = { showPwDialog = false })
    if (showDelete) DeleteAccountDialog(email = accountEmail, onConfirm = { conf -> showDelete = false; vm.deleteAccount(conf) {} }, onDismiss = { showDelete = false })
}

@Composable
private fun TwoFactorDialog(setup: SettingsViewModel.TwoFactorSetup, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tfa_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tfa_setup_hint))
                Text(setup.secret, style = MaterialTheme.typography.titleMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                OutlinedTextField(value = code, onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) }, label = { Text(stringResource(R.string.tfa_code)) }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(code) }, enabled = code.length == 6) { Text(stringResource(R.string.tfa_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChangePasswordDialog(onSubmit: (String, String) -> Unit, onDismiss: () -> Unit) {
    var cur by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pw_change_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = cur, onValueChange = { cur = it }, label = { Text(stringResource(R.string.pw_current)) }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboardOptions = de.ledgerline.app.ui.common.secretKeyboardOptions())
                OutlinedTextField(value = new, onValueChange = { new = it }, label = { Text(stringResource(R.string.pw_new)) }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboardOptions = de.ledgerline.app.ui.common.secretKeyboardOptions(), supportingText = { Text(stringResource(R.string.pw_min)) })
            }
        },
        confirmButton = { Button(onClick = { onSubmit(cur, new) }, enabled = cur.isNotBlank() && new.length >= 12) { Text(stringResource(R.string.pw_change_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DeleteAccountDialog(email: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var conf by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_delete)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.account_delete_warn))
                Text(stringResource(R.string.account_delete_confirm_hint, email ?: ""))
                OutlinedTextField(value = conf, onValueChange = { conf = it }, label = { Text(stringResource(R.string.account_email)) }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(conf) }, enabled = conf.isNotBlank() && conf.equals(email, ignoreCase = true), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.account_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Multi-select channel chips (desktop/ntfy/mail/webhook) for a contact-notify event type. */
@Composable
private fun ChannelRow(label: String, selected: List<String>, onToggle: (String) -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (ch in listOf("desktop", "ntfy", "mail", "webhook")) {
            androidx.compose.material3.FilterChip(
                selected = ch in selected,
                onClick = { onToggle(ch) },
                label = { Text(ch.replaceFirstChar { it.uppercase() }) },
            )
        }
    }
}

@Composable
private fun NotificationsSettings(padding: PaddingValues) {
    val vm: de.ledgerline.app.ui.notifications.NotificationsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    SubScreen(padding) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionHeader(
                if (state.unread > 0) stringResource(R.string.notifications_title_unread, state.unread)
                else stringResource(R.string.notifications_title),
            )
            if (state.items.any { !it.read }) {
                TextButton(onClick = vm::markAllRead) { Text(stringResource(R.string.notifications_mark_all)) }
            }
        }
        when {
            state.loading -> Text(
                stringResource(R.string.account_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(22.dp),
            )
            state.items.isEmpty() -> Text(
                stringResource(R.string.notifications_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(22.dp),
            )
            else -> Column(Modifier.padding(horizontal = 16.dp)) {
                SettingsGroup {
                    state.items.forEachIndexed { i, n ->
                        if (i > 0) SettingsRowDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val color = when (n.level) {
                                "error" -> MaterialTheme.colorScheme.error
                                "warning" -> MaterialTheme.colorScheme.tertiary
                                "success" -> Brand.accent
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (n.read) MaterialTheme.colorScheme.surfaceVariant else color))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(n.title, style = MaterialTheme.typography.bodyLarge)
                                val meta = listOfNotNull(n.body, n.at?.substringBefore('T')).joinToString(" · ")
                                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!n.read) TextButton(onClick = { vm.markRead(n.id) }) { Text(stringResource(R.string.notifications_mark_read)) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AboutSettings(padding: PaddingValues, onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    // "0.9.0 (137)" — marketing semver + monotonic git build number.
    val versionLine = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    // "a1b2c3d · main · 2026-07-29" (+ "·dirty" for an uncommitted local build).
    val build = buildString {
        append(BuildConfig.GIT_SHA)
        if (BuildConfig.GIT_DIRTY) append("·dirty")
        append(" · ").append(BuildConfig.GIT_BRANCH)
        append(" · ").append(BuildConfig.BUILD_DATE)
    }
    val diagnostics = "Ledgerline Android $versionLine\n$build"

    SubScreen(padding) {
        // Brand hero: gradient tile with the shield logo, app name, version.
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(96.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(percent = 28))
                    .background(Brand.accentGradient),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_ledgerline_logo),
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.Unspecified,
                    modifier = Modifier.size(64.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                versionLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodySmall, color = Brand.accent)
        }

        // Zero-knowledge assurance card.
        SectionHeader(stringResource(R.string.settings_about))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).cardSurface().padding(16.dp),
        ) {
            Text(stringResource(R.string.about_zk_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_zero_knowledge),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(R.string.about_contract),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // Build provenance — tap to copy for bug reports.
        SectionHeader(stringResource(R.string.about_build))
        ListItem(
            headlineContent = { Text(stringResource(R.string.about_build_id)) },
            supportingContent = {
                Text(build, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            },
            trailingContent = { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.about_copy)) },
            modifier = Modifier.clickable {
                de.ledgerline.app.ui.common.copyToClipboard(context, diagnostics)
                android.widget.Toast.makeText(context, R.string.about_copied, android.widget.Toast.LENGTH_SHORT).show()
            },
        )

        SectionHeader(stringResource(R.string.about_more))
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_licenses)) },
            supportingContent = { Text(stringResource(R.string.settings_licenses_sub)) },
            leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
            modifier = Modifier.clickable { onOpenLicenses() },
        )
    }
}

/** Static list of the app's open-source dependencies and their licenses (legal notice §5.10). */
private data class License(val name: String, val license: String, val url: String)

private val OSS_LICENSES = listOf(
    License("libsodium (lazysodium-android)", "ISC", "https://github.com/terl/lazysodium-android"),
    License("BouncyCastle", "MIT", "https://www.bouncycastle.org/licence.html"),
    License("MapLibre GL Native", "BSD-2-Clause", "https://github.com/maplibre/maplibre-native"),
    License("ZXing", "Apache-2.0", "https://github.com/zxing/zxing"),
    License("Retrofit", "Apache-2.0", "https://github.com/square/retrofit"),
    License("OkHttp", "Apache-2.0", "https://github.com/square/okhttp"),
    License("Jetpack Compose & AndroidX", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
    License("Kotlin & kotlinx.serialization", "Apache-2.0", "https://github.com/Kotlin"),
    License("Dagger Hilt", "Apache-2.0", "https://github.com/google/dagger"),
    License("JNA", "Apache-2.0 / LGPL-2.1", "https://github.com/java-native-access/jna"),
    License("Media3 (ExoPlayer)", "Apache-2.0", "https://github.com/androidx/media"),
)

@Composable
private fun LicensesScreen(padding: PaddingValues) {
    val context = LocalContext.current
    SubScreen(padding) {
        SectionHeader(stringResource(R.string.settings_licenses))
        OSS_LICENSES.forEach { lib ->
            ListItem(
                headlineContent = { Text(lib.name) },
                supportingContent = { Text(lib.license) },
                modifier = Modifier.clickable { openUrl(context, lib.url, chooser = false) },
            )
        }
    }
}

/** A labelled read-only account field: small caption label above the value. */
@Composable
private fun AccountField(label: String, value: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Shared scrolling container for a sub-screen — a single vertical scroll, no nesting. */
@Composable
private fun SubScreen(padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) { content() }
}

/* ------------------------------ Row helpers ------------------------------ */

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A labelled row of segmented options (e.g. km/mi) for the display-preference toggles. */
@Composable
private fun PrefChoiceRow(label: String, options: List<Pair<String, String>>, current: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        options.forEach { (value, disp) ->
            androidx.compose.material3.FilterChip(
                selected = current == value,
                onClick = { onSelect(value) },
                label = { Text(disp) },
            )
        }
    }
}

/**
 * A labelled radio-button group — mirrors the idle-timeout selector idiom (a label
 * [Text] above a [selectableGroup] of [RadioRow]s), used for the offline policy /
 * cache-limit selectors.
 */
@Composable
private fun SelectorGroup(title: String, enabled: Boolean, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Column(Modifier.selectableGroup()) { content() }
}

@Composable
private fun cacheLimitLabel(mb: Int): String = when (mb) {
    0 -> stringResource(R.string.settings_cache_unlimited)
    1024 -> "1 GB"
    2048 -> "2 GB"
    else -> "$mb MB"
}

@Composable
private fun timeoutLabel(minutes: Int): String = when (minutes) {
    1 -> stringResource(R.string.minutes_1)
    5 -> stringResource(R.string.minutes_5)
    10 -> stringResource(R.string.minutes_10)
    30 -> stringResource(R.string.minutes_30)
    else -> minutes.toString()
}

@Composable
private fun refreshLabel(seconds: Int): String = when (seconds) {
    0 -> stringResource(R.string.settings_refresh_off)
    60 -> stringResource(R.string.minutes_1)
    300 -> stringResource(R.string.minutes_5)
    900 -> stringResource(R.string.minutes_15)
    1800 -> stringResource(R.string.minutes_30)
    3600 -> stringResource(R.string.hours_1)
    10800 -> stringResource(R.string.hours_3)
    43200 -> stringResource(R.string.hours_12)
    86400 -> stringResource(R.string.hours_24)
    else -> seconds.toString()
}

@Composable
private fun keepScreenOnLabel(minutes: Int): String = when (minutes) {
    0 -> stringResource(R.string.settings_keep_screen_on_unlimited)
    5 -> stringResource(R.string.minutes_5)
    15 -> stringResource(R.string.minutes_15)
    30 -> stringResource(R.string.minutes_30)
    else -> minutes.toString()
}

@Composable
private fun daysLabel(days: Int): String = when (days) {
    1 -> stringResource(R.string.days_1)
    7 -> stringResource(R.string.days_7)
    14 -> stringResource(R.string.days_14)
    30 -> stringResource(R.string.days_30)
    else -> days.toString()
}

/** Reads the current app locale tag ("" = system default) via the AOSP LocaleManager. */
private fun currentLanguageTag(context: Context): String {
    val lm = context.getSystemService(LocaleManager::class.java)
    val locales = lm.applicationLocales
    return if (locales.isEmpty) "" else locales[0].language
}

/** Applies the app locale. Empty tag = follow the system default. */
private fun applyLanguage(context: Context, tag: String) {
    val lm = context.getSystemService(LocaleManager::class.java)
    lm.applicationLocales =
        if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
}

/**
 * §3.6 — informational client-integrity card: Keystore attestation level + root/tamper
 * heuristics. Advisory only (never blocks); a rooted privacy ROM is a warning, not a lockout.
 */
@Composable
private fun IntegrityCard(report: de.ledgerline.app.core.integrity.IntegrityReport?) {
    val clean = report?.clean == true
    val tint = if (clean) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Icon(
            if (clean) Icons.Outlined.VerifiedUser else Icons.Outlined.GppMaybe,
            contentDescription = null,
            tint = tint,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val attest = when (report?.attestation) {
                de.ledgerline.app.core.integrity.AttestationLevel.STRONGBOX -> stringResource(R.string.integrity_attest_strongbox)
                de.ledgerline.app.core.integrity.AttestationLevel.TEE -> stringResource(R.string.integrity_attest_tee)
                de.ledgerline.app.core.integrity.AttestationLevel.SOFTWARE -> stringResource(R.string.integrity_attest_software)
                else -> stringResource(R.string.integrity_attest_unverified)
            }
            Text(
                if (clean) stringResource(R.string.integrity_ok) else stringResource(R.string.integrity_warning),
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
            )
            Text(
                stringResource(R.string.integrity_attest_label, attest),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report?.rooted == true) {
                Text(
                    stringResource(R.string.integrity_root_detected, report.rootReasons.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
