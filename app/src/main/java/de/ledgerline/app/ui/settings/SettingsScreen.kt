package de.ledgerline.app.ui.settings

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.BuildConfig
import de.ledgerline.app.R
import de.ledgerline.app.data.ContactSort
import de.ledgerline.app.data.DateFormatPref
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.offline.ContactBlobPolicy
import de.ledgerline.app.data.offline.FileBlobPolicy
import de.ledgerline.app.data.offline.PhotoBlobPolicy
import de.ledgerline.app.data.remote.dto.MeUser
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.SectionHeader
import de.ledgerline.app.ui.ops.OpProgressOverlay
import de.ledgerline.app.ui.workspace.common.humanSize
import kotlinx.coroutines.launch

/** Internal Settings destinations — a categorized landing (ROOT) plus one sub-screen per category. */
private enum class SettingsRoute { ROOT, APPEARANCE, SECURITY, OFFLINE, BACKGROUND, ACCOUNT, ABOUT }

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
    val backgroundOps by vm.backgroundOpsEnabled.collectAsStateWithLifecycle()
    val linkChooser by vm.linkChooserEnabled.collectAsStateWithLifecycle()
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
    val contactSort by vm.contactSort.collectAsStateWithLifecycle()
    val dateFormat by vm.dateFormat.collectAsStateWithLifecycle()

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

    // Back: a sub-screen returns to the landing list; the landing list exits Settings.
    BackHandler {
        if (route == SettingsRoute.ROOT) onBack() else route = SettingsRoute.ROOT
    }

    val title = when (route) {
        SettingsRoute.ROOT -> stringResource(R.string.settings_title)
        SettingsRoute.APPEARANCE -> stringResource(R.string.settings_cat_appearance)
        SettingsRoute.SECURITY -> stringResource(R.string.settings_cat_security)
        SettingsRoute.OFFLINE -> stringResource(R.string.settings_cat_offline)
        SettingsRoute.BACKGROUND -> stringResource(R.string.settings_cat_background)
        SettingsRoute.ACCOUNT -> stringResource(R.string.settings_cat_account)
        SettingsRoute.ABOUT -> stringResource(R.string.settings_cat_about)
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
                SettingsRoute.ROOT -> SettingsRoot(innerPadding) { route = it }

                SettingsRoute.APPEARANCE -> AppearanceSettings(
                    padding = innerPadding,
                    currentLang = currentLang,
                    onSelectLang = { tag -> applyLanguage(context, tag); currentLang = tag },
                    contactSort = contactSort,
                    onSelectContactSort = vm::setContactSort,
                    dateFormat = dateFormat,
                    onSelectDateFormat = vm::setDateFormat,
                )

                SettingsRoute.SECURITY -> SecuritySettings(
                    padding = innerPadding,
                    timeout = timeout,
                    onSetTimeout = vm::setTimeoutMinutes,
                    onLockNow = onLockNow,
                )

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

                SettingsRoute.ACCOUNT -> AccountSettings(
                    padding = innerPadding,
                    account = account,
                    onDisconnect = { showDisconnectConfirm = true },
                )

                SettingsRoute.ABOUT -> AboutSettings(innerPadding)
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
private fun SettingsRoot(padding: PaddingValues, onNavigate: (SettingsRoute) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        CategoryRow(
            icon = Icons.Outlined.Language,
            title = stringResource(R.string.settings_cat_appearance),
            subtitle = stringResource(R.string.settings_cat_appearance_sub),
        ) { onNavigate(SettingsRoute.APPEARANCE) }
        CategoryRow(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.settings_cat_security),
            subtitle = stringResource(R.string.settings_cat_security_sub),
        ) { onNavigate(SettingsRoute.SECURITY) }
        CategoryRow(
            icon = Icons.Outlined.CloudOff,
            title = stringResource(R.string.settings_cat_offline),
            subtitle = stringResource(R.string.settings_cat_offline_sub),
        ) { onNavigate(SettingsRoute.OFFLINE) }
        CategoryRow(
            icon = Icons.Outlined.Sync,
            title = stringResource(R.string.settings_cat_background),
            subtitle = stringResource(R.string.settings_cat_background_sub),
        ) { onNavigate(SettingsRoute.BACKGROUND) }
        CategoryRow(
            icon = Icons.Outlined.AccountCircle,
            title = stringResource(R.string.settings_cat_account),
            subtitle = stringResource(R.string.settings_cat_account_sub),
        ) { onNavigate(SettingsRoute.ACCOUNT) }
        CategoryRow(
            icon = Icons.Outlined.Info,
            title = stringResource(R.string.settings_cat_about),
            subtitle = stringResource(R.string.settings_cat_about_sub),
        ) { onNavigate(SettingsRoute.ABOUT) }
    }
}

/** A single tappable category row on a tonal surface: leading icon + title + short subtitle. */
@Composable
private fun CategoryRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
    )
}

/* --------------------------- Category sub-screens --------------------------- */

@Composable
private fun AppearanceSettings(
    padding: PaddingValues,
    currentLang: String,
    onSelectLang: (String) -> Unit,
    contactSort: ContactSort,
    onSelectContactSort: (ContactSort) -> Unit,
    dateFormat: DateFormatPref,
    onSelectDateFormat: (DateFormatPref) -> Unit,
) {
    SubScreen(padding) {
        SectionHeader(stringResource(R.string.settings_language))
        Column(Modifier.selectableGroup()) {
            RadioRow(stringResource(R.string.settings_language_system), currentLang == "") {
                onSelectLang("")
            }
            RadioRow(stringResource(R.string.settings_language_de), currentLang == "de") {
                onSelectLang("de")
            }
            RadioRow(stringResource(R.string.settings_language_en), currentLang == "en") {
                onSelectLang("en")
            }
        }

        SectionHeader(stringResource(R.string.settings_contact_sort))
        Column(Modifier.selectableGroup()) {
            RadioRow(stringResource(R.string.settings_contact_sort_first), contactSort == ContactSort.FIRST) {
                onSelectContactSort(ContactSort.FIRST)
            }
            RadioRow(stringResource(R.string.settings_contact_sort_last), contactSort == ContactSort.LAST) {
                onSelectContactSort(ContactSort.LAST)
            }
            RadioRow(stringResource(R.string.settings_contact_sort_display), contactSort == ContactSort.DISPLAY) {
                onSelectContactSort(ContactSort.DISPLAY)
            }
        }

        SectionHeader(stringResource(R.string.settings_date_format))
        Column(Modifier.selectableGroup()) {
            RadioRow(stringResource(R.string.settings_date_format_system), dateFormat == DateFormatPref.SYSTEM) {
                onSelectDateFormat(DateFormatPref.SYSTEM)
            }
            RadioRow(stringResource(R.string.settings_date_format_dmy), dateFormat == DateFormatPref.DMY) {
                onSelectDateFormat(DateFormatPref.DMY)
            }
            RadioRow(stringResource(R.string.settings_date_format_ymd), dateFormat == DateFormatPref.YMD) {
                onSelectDateFormat(DateFormatPref.YMD)
            }
            RadioRow(stringResource(R.string.settings_date_format_mdy), dateFormat == DateFormatPref.MDY) {
                onSelectDateFormat(DateFormatPref.MDY)
            }
        }
    }
}

@Composable
private fun SecuritySettings(
    padding: PaddingValues,
    timeout: Int,
    onSetTimeout: (Int) -> Unit,
    onLockNow: () -> Unit,
) {
    SubScreen(padding) {
        SectionHeader(stringResource(R.string.settings_security))
        Text(
            stringResource(R.string.settings_idle_timeout),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Column(Modifier.selectableGroup()) {
            SettingsStore.TIMEOUT_OPTIONS.forEach { minutes ->
                RadioRow(timeoutLabel(minutes), timeout == minutes) { onSetTimeout(minutes) }
            }
        }
        OutlinedButton(
            onClick = onLockNow,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.settings_lock_now)) }
        Text(
            stringResource(R.string.settings_lock_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

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
        SectionHeader(stringResource(R.string.settings_offline_section))
        SwitchRow(
            title = stringResource(R.string.settings_offline_title),
            subtitle = stringResource(R.string.settings_offline_subtitle),
            checked = offlineEnabled,
            onCheckedChange = onSetOffline,
        )
        // Files policy: Off / On demand / All.
        SelectorGroup(
            title = stringResource(R.string.settings_files_policy),
            enabled = offlineEnabled,
        ) {
            RadioRow(stringResource(R.string.policy_off), filesPolicy == FileBlobPolicy.OFF, offlineEnabled) {
                onSetFilesPolicy(FileBlobPolicy.OFF)
            }
            RadioRow(stringResource(R.string.policy_on_demand), filesPolicy == FileBlobPolicy.ON_DEMAND, offlineEnabled) {
                onSetFilesPolicy(FileBlobPolicy.ON_DEMAND)
            }
            RadioRow(stringResource(R.string.policy_all), filesPolicy == FileBlobPolicy.ALL, offlineEnabled) {
                onSetFilesPolicy(FileBlobPolicy.ALL)
            }
        }

        // Photos policy: Off / Thumbnails / On demand / All.
        SelectorGroup(
            title = stringResource(R.string.settings_photos_policy),
            enabled = offlineEnabled,
        ) {
            RadioRow(stringResource(R.string.policy_off), photosPolicy == PhotoBlobPolicy.OFF, offlineEnabled) {
                onSetPhotosPolicy(PhotoBlobPolicy.OFF)
            }
            RadioRow(stringResource(R.string.policy_thumbs), photosPolicy == PhotoBlobPolicy.THUMBS, offlineEnabled) {
                onSetPhotosPolicy(PhotoBlobPolicy.THUMBS)
            }
            RadioRow(stringResource(R.string.policy_on_demand), photosPolicy == PhotoBlobPolicy.ON_DEMAND, offlineEnabled) {
                onSetPhotosPolicy(PhotoBlobPolicy.ON_DEMAND)
            }
            RadioRow(stringResource(R.string.policy_all), photosPolicy == PhotoBlobPolicy.ALL, offlineEnabled) {
                onSetPhotosPolicy(PhotoBlobPolicy.ALL)
            }
        }

        // Contact avatars policy: Off / On demand / All.
        SelectorGroup(
            title = stringResource(R.string.settings_contacts_policy),
            enabled = offlineEnabled,
        ) {
            RadioRow(stringResource(R.string.policy_off), contactsPolicy == ContactBlobPolicy.OFF, offlineEnabled) {
                onSetContactsPolicy(ContactBlobPolicy.OFF)
            }
            RadioRow(stringResource(R.string.policy_on_demand), contactsPolicy == ContactBlobPolicy.ON_DEMAND, offlineEnabled) {
                onSetContactsPolicy(ContactBlobPolicy.ON_DEMAND)
            }
            RadioRow(stringResource(R.string.policy_all), contactsPolicy == ContactBlobPolicy.ALL, offlineEnabled) {
                onSetContactsPolicy(ContactBlobPolicy.ALL)
            }
        }

        // Notes/todos/bookmarks/contacts records ride the sealed manifest (master switch).
        Text(
            stringResource(R.string.settings_offline_manifest_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // Cache size limit: 512 MB / 1 GB / 2 GB / Unlimited.
        SelectorGroup(
            title = stringResource(R.string.settings_cache_limit),
            enabled = offlineEnabled,
        ) {
            SettingsStore.CACHE_MAX_MB_OPTIONS.forEach { mb ->
                RadioRow(cacheLimitLabel(mb), cacheMaxMb == mb, offlineEnabled) { onSetCacheMaxMb(mb) }
            }
        }

        SwitchRow(
            title = stringResource(R.string.settings_prefetch_wifi),
            subtitle = "",
            checked = prefetchWifiOnly,
            enabled = offlineEnabled,
            onCheckedChange = onSetWifiOnly,
        )
        SwitchRow(
            title = stringResource(R.string.settings_prefetch_charging),
            subtitle = "",
            checked = prefetchChargingOnly,
            enabled = offlineEnabled,
            onCheckedChange = onSetChargingOnly,
        )
        OutlinedButton(
            onClick = onPrefetchNow,
            enabled = offlineEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.settings_prefetch_now)) }

        Text(
            stringResource(R.string.settings_offline_size, humanSize(cacheSize)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        TextButton(
            onClick = onClearCache,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(stringResource(R.string.settings_offline_clear)) }
    }
}

@Composable
private fun BackgroundSettings(
    padding: PaddingValues,
    backgroundOps: Boolean,
    linkChooser: Boolean,
    onSetBackgroundOps: (Boolean) -> Unit,
    onSetLinkChooser: (Boolean) -> Unit,
) {
    SubScreen(padding) {
        SwitchRow(
            title = stringResource(R.string.settings_background_ops_title),
            subtitle = stringResource(R.string.settings_background_ops_subtitle),
            checked = backgroundOps,
            onCheckedChange = onSetBackgroundOps,
        )
        SwitchRow(
            title = stringResource(R.string.settings_link_chooser),
            subtitle = "",
            checked = linkChooser,
            onCheckedChange = onSetLinkChooser,
        )
    }
}

@Composable
private fun AccountSettings(
    padding: PaddingValues,
    account: MeUser?,
    onDisconnect: () -> Unit,
) {
    SubScreen(padding) {
        SectionHeader(stringResource(R.string.settings_account))
        if (account != null) {
            AccountField(stringResource(R.string.account_name), account.name ?: "—")
            AccountField(stringResource(R.string.account_email), account.email ?: "—")
            if (account.groups.isNotEmpty()) {
                Text(
                    stringResource(R.string.account_groups),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    account.groups.forEach { g ->
                        AssistChip(onClick = {}, label = { Text(g) })
                    }
                }
            }
        } else {
            Text(
                stringResource(R.string.account_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Button(
            onClick = onDisconnect,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.settings_disconnect)) }
    }
}

@Composable
private fun AboutSettings(padding: PaddingValues) {
    SubScreen(padding) {
        SectionHeader(stringResource(R.string.settings_about))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.settings_zero_knowledge),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
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
