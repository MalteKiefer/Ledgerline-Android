package de.ledgerline.app.ui.gallery

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.R
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.gallery.BackgroundCredStore
import de.ledgerline.app.data.gallery.GalleryBackup
import de.ledgerline.app.data.gallery.GalleryBackupWorker
import de.ledgerline.app.data.gallery.GalleryRepository
import de.ledgerline.app.domain.model.gallery.GalleryAlbum
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.SectionLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the camera-roll backup settings screen needs — no gallery timeline load. */
@HiltViewModel
class GalleryBackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: android.content.Context,
    private val repo: GalleryRepository,
    private val settings: SettingsStore,
    private val backup: GalleryBackup,
    private val sessionHolder: SessionHolder,
    private val bgCred: BackgroundCredStore,
) : ViewModel() {
    private fun <T> flag(f: Flow<T>, initial: T) = f.stateIn(viewModelScope, SharingStarted.Eagerly, initial)
    val enabled = flag(settings.galleryBackupEnabled, false)
    val wifiOnly = flag(settings.galleryBackupWifiOnly, true)
    val videos = flag(settings.galleryBackupVideos, true)
    val charging = flag(settings.galleryBackupCharging, false)
    val batteryOk = flag(settings.galleryBackupBatteryOk, true)
    val idle = flag(settings.galleryBackupIdle, false)
    val deleteAfter = flag(settings.galleryBackupDeleteAfter, false)
    val background = flag(settings.galleryBackupBackground, false)
    val albumId = flag(settings.galleryBackupAlbumId, 0)
    val excludedBuckets = flag(settings.galleryBackupExcludedBuckets, emptySet())
    val status = backup.status
    val pendingDeletes = backup.pendingDeletes

    fun hasPermission() = backup.hasPermission()
    fun clearPendingDeletes() = backup.clearPendingDeletes()
    fun backupNow(all: Boolean) = backup.runNow(all)

    fun setEnabled(on: Boolean) = viewModelScope.launch { settings.setGalleryBackupEnabled(on); if (on) backup.runNow(false) }
    fun setWifiOnly(on: Boolean) = viewModelScope.launch { settings.setGalleryBackupWifiOnly(on); reschedule() }
    fun setVideos(on: Boolean) = viewModelScope.launch { settings.setGalleryBackupVideos(on) }
    fun setCharging(on: Boolean) = viewModelScope.launch { settings.setGalleryBackupCharging(on); reschedule() }
    fun setBatteryOk(on: Boolean) = viewModelScope.launch { settings.setGalleryBackupBatteryOk(on); reschedule() }
    fun setIdle(on: Boolean) = viewModelScope.launch { settings.setGalleryBackupIdle(on); reschedule() }
    fun setDeleteAfter(on: Boolean) = viewModelScope.launch { settings.setGalleryBackupDeleteAfter(on) }
    fun setAlbum(id: Int) = viewModelScope.launch { settings.setGalleryBackupAlbumId(id) }

    fun setBackground(on: Boolean) = viewModelScope.launch {
        settings.setGalleryBackupBackground(on)
        if (on) { sessionHolder.get()?.let { bgCred.save(it) }; reschedule() }
        else { bgCred.clear(); GalleryBackupWorker.cancel(context) }
    }

    /** Re-enqueue the periodic worker with the current constraints (only while background is on). */
    private suspend fun reschedule() {
        if (!settings.galleryBackupBackground.first()) return
        GalleryBackupWorker.schedule(
            context,
            wifiOnly = settings.galleryBackupWifiOnly.first(),
            charging = settings.galleryBackupCharging.first(),
            batteryOk = settings.galleryBackupBatteryOk.first(),
            idle = settings.galleryBackupIdle.first(),
        )
    }

    suspend fun albums() = repo.albums()
    suspend fun buckets() = backup.deviceBuckets(settings.galleryBackupVideos.first())
    fun setBucketExcluded(id: String, excluded: Boolean) = viewModelScope.launch { settings.setGalleryBackupBucketExcluded(id, excluded) }
}

/** Full-screen camera-roll backup settings (from the account settings or the gallery cloud action). */
@Composable
fun GalleryBackupScreen(onBack: () -> Unit, vm: GalleryBackupViewModel = hiltViewModel()) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val charging by vm.charging.collectAsStateWithLifecycle()
    val batteryOk by vm.batteryOk.collectAsStateWithLifecycle()
    val idle by vm.idle.collectAsStateWithLifecycle()
    val deleteAfter by vm.deleteAfter.collectAsStateWithLifecycle()
    val background by vm.background.collectAsStateWithLifecycle()
    val albumId by vm.albumId.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val pendingDeletes by vm.pendingDeletes.collectAsStateWithLifecycle()

    val excludedBuckets by vm.excludedBuckets.collectAsStateWithLifecycle()
    var albums by remember { mutableStateOf<List<GalleryAlbum>?>(null) }
    var buckets by remember { mutableStateOf<List<de.ledgerline.app.data.gallery.GalleryBackup.Bucket>>(emptyList()) }
    LaunchedEffect(Unit) { albums = vm.albums() }
    LaunchedEffect(enabled) { if (vm.hasPermission()) buckets = vm.buckets() }
    var albumMenu by remember { mutableStateOf(false) }

    val perms = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g ->
        if (g.values.any { it }) vm.setEnabled(true)
    }
    fun enableWithPerm() { if (vm.hasPermission()) vm.setEnabled(true) else permLauncher.launch(perms) }
    fun requireThen(block: () -> Unit) { if (vm.hasPermission()) block() else permLauncher.launch(perms) }

    // Delete-after consent (foreground): confirm each batch via MediaStore.createDeleteRequest.
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { vm.clearPendingDeletes() }
    LaunchedEffect(pendingDeletes) {
        if (pendingDeletes.isNotEmpty()) {
            val pi = android.provider.MediaStore.createDeleteRequest(ctx.contentResolver, pendingDeletes)
            runCatching { deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }.onFailure { vm.clearPendingDeletes() }
        }
    }

    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.gallery_backup), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.gallery_backup_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            ToggleRow(stringResource(R.string.gallery_backup_enable), enabled) { on -> if (on) enableWithPerm() else vm.setEnabled(false) }

            SectionLabel(stringResource(R.string.gallery_backup_conditions))
            ToggleRow(stringResource(R.string.gallery_backup_wifi), wifiOnly, vm::setWifiOnly)
            ToggleRow(stringResource(R.string.gallery_backup_charging), charging, vm::setCharging)
            ToggleRow(stringResource(R.string.gallery_backup_battery_ok), batteryOk, vm::setBatteryOk)
            ToggleRow(stringResource(R.string.gallery_backup_idle), idle, vm::setIdle)

            SectionLabel(stringResource(R.string.gallery_backup_what))
            ToggleRow(stringResource(R.string.gallery_backup_videos), videos, vm::setVideos)
            Row(Modifier.fillMaxWidth().clickable { albumMenu = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gallery_backup_album), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Box {
                    Text(albums?.firstOrNull { it.id == albumId }?.name ?: stringResource(R.string.gallery_backup_album_none), color = MaterialTheme.colorScheme.primary)
                    DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.gallery_backup_album_none)) }, onClick = { albumMenu = false; vm.setAlbum(0) })
                        albums.orEmpty().forEach { a -> DropdownMenuItem(text = { Text(a.name) }, onClick = { albumMenu = false; vm.setAlbum(a.id) }) }
                    }
                }
            }
            ToggleRow(stringResource(R.string.gallery_backup_delete_after), deleteAfter, vm::setDeleteAfter)

            // Device folders — on = included; switch off to exclude a folder from backup.
            if (buckets.isNotEmpty()) {
                SectionLabel(stringResource(R.string.gallery_backup_folders))
                Text(stringResource(R.string.gallery_backup_folders_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                buckets.forEach { b ->
                    ToggleRow("${b.name} (${b.count})", checked = b.id !in excludedBuckets) { on -> vm.setBucketExcluded(b.id, !on) }
                }
            }

            SectionLabel(stringResource(R.string.gallery_backup_background))
            ToggleRow(stringResource(R.string.gallery_backup_background), background, vm::setBackground)
            if (background) Text(stringResource(R.string.gallery_backup_background_warn), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (status.running) {
                LinearProgressIndicator(progress = { if (status.total > 0) status.done.toFloat() / status.total else 0f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.gallery_backup_progress, status.done, status.total), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { requireThen { vm.backupNow(false) } }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.gallery_backup_now)) }
                OutlinedButton(onClick = { requireThen { vm.backupNow(true) } }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.gallery_backup_all)) }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
