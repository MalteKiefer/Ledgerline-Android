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
    val offlineEnabled by vm.offlineEnabled.collectAsStateWithLifecycle()
    val filesBlobsOffline by vm.filesBlobsOffline.collectAsStateWithLifecycle()
    val photosBlobsOffline by vm.photosBlobsOffline.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSizeBytes.collectAsStateWithLifecycle()
    var currentLang by remember { mutableStateOf(currentLanguageTag(context)) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }

    // Compute the cache-size line once when the Settings tab is shown.
    LaunchedEffect(Unit) { vm.refreshCacheSize() }

    // Result is ignored: if the user denies notifications the op still runs; the
    // platform simply suppresses the foreground-service notification.
    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        modifier
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

        // Offline
        SectionHeader(stringResource(R.string.settings_offline_section))
        SwitchRow(
            title = stringResource(R.string.settings_offline_title),
            subtitle = stringResource(R.string.settings_offline_subtitle),
            checked = offlineEnabled,
            onCheckedChange = { vm.setOfflineEnabled(it) },
        )
        SwitchRow(
            title = stringResource(R.string.settings_offline_files),
            subtitle = "",
            checked = filesBlobsOffline,
            enabled = offlineEnabled,
            onCheckedChange = { vm.setFilesBlobsOffline(it) },
        )
        SwitchRow(
            title = stringResource(R.string.settings_offline_photos),
            subtitle = "",
            checked = photosBlobsOffline,
            enabled = offlineEnabled,
            onCheckedChange = { vm.setPhotosBlobsOffline(it) },
        )
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
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
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
