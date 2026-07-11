package de.ledgerline.app.ui.settings

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.BuildConfig
import de.ledgerline.app.R
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.offline.FileBlobPolicy
import de.ledgerline.app.data.offline.PhotoBlobPolicy
import de.ledgerline.app.ui.ops.OpProgressOverlay
import de.ledgerline.app.ui.workspace.common.humanSize
import kotlinx.coroutines.launch

/**
 * Settings body — scrollable sections only, no Scaffold or TopAppBar.
 * Intended to be embedded directly in [WorkspaceScaffold] as the Settings tab content.
 */
@Composable
fun SettingsContent(
    modifier: Modifier = Modifier,
    onLockNow: () -> Unit,
    onDisconnected: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeout by vm.timeoutMinutes.collectAsStateWithLifecycle()
    val backgroundOps by vm.backgroundOpsEnabled.collectAsStateWithLifecycle()
    val linkChooser by vm.linkChooserEnabled.collectAsStateWithLifecycle()
    val offlineEnabled by vm.offlineEnabled.collectAsStateWithLifecycle()
    val filesPolicy by vm.filesPolicy.collectAsStateWithLifecycle()
    val photosPolicy by vm.photosPolicy.collectAsStateWithLifecycle()
    val cacheMaxMb by vm.cacheMaxMb.collectAsStateWithLifecycle()
    val prefetchWifiOnly by vm.prefetchWifiOnly.collectAsStateWithLifecycle()
    val prefetchChargingOnly by vm.prefetchChargingOnly.collectAsStateWithLifecycle()
    val prefetchMessage by vm.prefetchMessage.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSizeBytes.collectAsStateWithLifecycle()
    var currentLang by remember { mutableStateOf(currentLanguageTag(context)) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val constraintsMsg = stringResource(R.string.settings_prefetch_constraints)

    // Compute the cache-size line once when the Settings tab is shown.
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

    Box(modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Language
        SectionHeader(stringResource(R.string.settings_language))
        Column(Modifier.selectableGroup()) {
            RadioRow(stringResource(R.string.settings_language_system), currentLang == "") {
                applyLanguage(context, ""); currentLang = ""
            }
            RadioRow(stringResource(R.string.settings_language_de), currentLang == "de") {
                applyLanguage(context, "de"); currentLang = "de"
            }
            RadioRow(stringResource(R.string.settings_language_en), currentLang == "en") {
                applyLanguage(context, "en"); currentLang = "en"
            }
        }

        // Security
        SectionHeader(stringResource(R.string.settings_security))
        Text(
            stringResource(R.string.settings_idle_timeout),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Column(Modifier.selectableGroup()) {
            SettingsStore.TIMEOUT_OPTIONS.forEach { minutes ->
                RadioRow(timeoutLabel(minutes), timeout == minutes) { vm.setTimeoutMinutes(minutes) }
            }
        }
        OutlinedButton(
            onClick = onLockNow,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.settings_lock_now)) }

        SwitchRow(
            title = stringResource(R.string.settings_background_ops_title),
            subtitle = stringResource(R.string.settings_background_ops_subtitle),
            checked = backgroundOps,
            onCheckedChange = { enabled ->
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
        )

        SwitchRow(
            title = stringResource(R.string.settings_link_chooser),
            subtitle = "",
            checked = linkChooser,
            onCheckedChange = { vm.setLinkChooserEnabled(it) },
        )

        // Offline
        SectionHeader(stringResource(R.string.settings_offline_section))
        SwitchRow(
            title = stringResource(R.string.settings_offline_title),
            subtitle = stringResource(R.string.settings_offline_subtitle),
            checked = offlineEnabled,
            onCheckedChange = { vm.setOfflineEnabled(it) },
        )
        // Files policy: Off / On demand / All.
        SelectorGroup(
            title = stringResource(R.string.settings_files_policy),
            enabled = offlineEnabled,
        ) {
            RadioRow(stringResource(R.string.policy_off), filesPolicy == FileBlobPolicy.OFF, offlineEnabled) {
                vm.setFilesPolicy(FileBlobPolicy.OFF)
            }
            RadioRow(stringResource(R.string.policy_on_demand), filesPolicy == FileBlobPolicy.ON_DEMAND, offlineEnabled) {
                vm.setFilesPolicy(FileBlobPolicy.ON_DEMAND)
            }
            RadioRow(stringResource(R.string.policy_all), filesPolicy == FileBlobPolicy.ALL, offlineEnabled) {
                vm.setFilesPolicy(FileBlobPolicy.ALL)
            }
        }

        // Photos policy: Off / Thumbnails / On demand / All.
        SelectorGroup(
            title = stringResource(R.string.settings_photos_policy),
            enabled = offlineEnabled,
        ) {
            RadioRow(stringResource(R.string.policy_off), photosPolicy == PhotoBlobPolicy.OFF, offlineEnabled) {
                vm.setPhotosPolicy(PhotoBlobPolicy.OFF)
            }
            RadioRow(stringResource(R.string.policy_thumbs), photosPolicy == PhotoBlobPolicy.THUMBS, offlineEnabled) {
                vm.setPhotosPolicy(PhotoBlobPolicy.THUMBS)
            }
            RadioRow(stringResource(R.string.policy_on_demand), photosPolicy == PhotoBlobPolicy.ON_DEMAND, offlineEnabled) {
                vm.setPhotosPolicy(PhotoBlobPolicy.ON_DEMAND)
            }
            RadioRow(stringResource(R.string.policy_all), photosPolicy == PhotoBlobPolicy.ALL, offlineEnabled) {
                vm.setPhotosPolicy(PhotoBlobPolicy.ALL)
            }
        }

        // Cache size limit: 512 MB / 1 GB / 2 GB / Unlimited.
        SelectorGroup(
            title = stringResource(R.string.settings_cache_limit),
            enabled = offlineEnabled,
        ) {
            SettingsStore.CACHE_MAX_MB_OPTIONS.forEach { mb ->
                RadioRow(cacheLimitLabel(mb), cacheMaxMb == mb, offlineEnabled) { vm.setCacheMaxMb(mb) }
            }
        }

        SwitchRow(
            title = stringResource(R.string.settings_prefetch_wifi),
            subtitle = "",
            checked = prefetchWifiOnly,
            enabled = offlineEnabled,
            onCheckedChange = { vm.setPrefetchWifiOnly(it) },
        )
        SwitchRow(
            title = stringResource(R.string.settings_prefetch_charging),
            subtitle = "",
            checked = prefetchChargingOnly,
            enabled = offlineEnabled,
            onCheckedChange = { vm.setPrefetchChargingOnly(it) },
        )
        OutlinedButton(
            onClick = { vm.prefetchNow() },
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
            onClick = { showClearCacheConfirm = true },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(stringResource(R.string.settings_offline_clear)) }

        // Account
        SectionHeader(stringResource(R.string.settings_account))
        Button(
            onClick = { showDisconnectConfirm = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.settings_disconnect)) }

        // About
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
        // Shared op overlay: a manual "Prefetch now" (OpKind.PREFETCH) shows here.
        OpProgressOverlay()
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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

@Composable
private fun SectionHeader(text: String) {
    HorizontalDivider()
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 4.dp),
    )
}

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
