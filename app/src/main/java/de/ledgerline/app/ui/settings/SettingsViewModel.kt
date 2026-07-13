package de.ledgerline.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.Prefetcher
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.security.IdleLocker
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.core.backup.GalleryBackupManager
import de.ledgerline.app.data.AccountRepository
import de.ledgerline.app.data.ContactSort
import de.ledgerline.app.data.DateFormatPref
import de.ledgerline.app.data.SessionStore
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.backup.BackupStateStore
import de.ledgerline.app.data.backup.DeviceAlbum
import de.ledgerline.app.data.backup.DeviceAlbums
import de.ledgerline.app.data.offline.ContactBlobPolicy
import de.ledgerline.app.data.offline.FileBlobPolicy
import de.ledgerline.app.data.offline.PhotoBlobPolicy
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.MeUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val idleLocker: IdleLocker,
    private val sessionHolder: SessionHolder,
    private val sessionStore: SessionStore,
    private val vaultKeyHolder: VaultKeyHolder,
    private val workspaceCache: WorkspaceCache,
    private val keystoreSealer: KeystoreSealer,
    private val storeCache: StoreDiskCache,
    private val blobCache: BlobDiskCache,
    private val prefetcher: Prefetcher,
    private val accountRepository: AccountRepository,
    private val backupManager: GalleryBackupManager,
    private val deviceAlbums: DeviceAlbums,
    private val backupStateStore: BackupStateStore,
) : ViewModel() {

    /** Signed-in account (name/email/groups), fetched once from `/api/v1/me`; null while
     *  loading, offline, or on failure — the Account screen degrades gracefully. */
    private val _account = MutableStateFlow<MeUser?>(null)
    val account: StateFlow<MeUser?> = _account.asStateFlow()

    init {
        viewModelScope.launch { _account.value = accountRepository.me() }
    }

    val backupEnabled: StateFlow<Boolean> = settingsStore.backupEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val backupAlbumIds: StateFlow<Set<String>> = settingsStore.backupAlbumIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _albums = MutableStateFlow<List<DeviceAlbum>>(emptyList())
    val albums: StateFlow<List<DeviceAlbum>> = _albums.asStateFlow()

    private val _backedUpCount = MutableStateFlow(0)
    val backedUpCount: StateFlow<Int> = _backedUpCount.asStateFlow()

    fun loadAlbums() = viewModelScope.launch {
        _albums.value = withContext(Dispatchers.IO) { deviceAlbums.list() }
        _backedUpCount.value = backupStateStore.backedUpIds().size
    }

    fun setBackupEnabled(on: Boolean) = viewModelScope.launch {
        settingsStore.setBackupEnabled(on)
        if (on) backupManager.maybeRun()
    }

    fun toggleAlbum(bucketId: String) = viewModelScope.launch {
        val cur = settingsStore.backupAlbumIds.first().toMutableSet()
        if (!cur.add(bucketId)) cur.remove(bucketId)
        settingsStore.setBackupAlbumIds(cur)
    }

    fun backupNow() = backupManager.maybeRun()

    /** Current idle-lock timeout in minutes, backed by the plaintext settings store. */
    val timeoutMinutes: StateFlow<Int> = settingsStore.timeoutMinutes
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_TIMEOUT_MINUTES)

    fun setTimeoutMinutes(minutes: Int) {
        idleLocker.setTimeoutMs(minutes * 60_000L)
        viewModelScope.launch { settingsStore.setTimeoutMinutes(minutes) }
    }

    /** Whether background operations may keep running after the app is backgrounded. */
    val backgroundOpsEnabled: StateFlow<Boolean> = settingsStore.backgroundOpsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setBackgroundOpsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setBackgroundOpsEnabled(enabled) }
    }

    /** Contact-list sort order. */
    val contactSort: StateFlow<ContactSort> = settingsStore.contactSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, ContactSort.FIRST)

    fun setContactSort(sort: ContactSort) {
        viewModelScope.launch { settingsStore.setContactSort(sort) }
    }

    /** Date display format. */
    val dateFormat: StateFlow<DateFormatPref> = settingsStore.dateFormat
        .stateIn(viewModelScope, SharingStarted.Eagerly, DateFormatPref.SYSTEM)

    fun setDateFormat(fmt: DateFormatPref) {
        viewModelScope.launch { settingsStore.setDateFormat(fmt) }
    }

    /** Master offline-cache switch; when off the per-module blob toggles are disabled. */
    val offlineEnabled: StateFlow<Boolean> = settingsStore.offlineEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setOfflineEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setOfflineEnabled(enabled) }
    }

    /** File-content blob caching policy (Off / On demand / All). */
    val filesPolicy: StateFlow<FileBlobPolicy> = settingsStore.filesPolicy
        .stateIn(viewModelScope, SharingStarted.Eagerly, FileBlobPolicy.ON_DEMAND)

    fun setFilesPolicy(p: FileBlobPolicy) {
        viewModelScope.launch { settingsStore.setFilesPolicy(p) }
    }

    /** Photo blob caching policy (Off / Thumbnails / On demand / All). */
    val photosPolicy: StateFlow<PhotoBlobPolicy> = settingsStore.photosPolicy
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhotoBlobPolicy.ON_DEMAND)

    fun setPhotosPolicy(p: PhotoBlobPolicy) {
        viewModelScope.launch { settingsStore.setPhotosPolicy(p) }
    }

    /** Contact-avatar blob caching policy (Off / On demand / All). */
    val contactsPolicy: StateFlow<ContactBlobPolicy> = settingsStore.contactsPolicy
        .stateIn(viewModelScope, SharingStarted.Eagerly, ContactBlobPolicy.ON_DEMAND)

    fun setContactsPolicy(p: ContactBlobPolicy) {
        viewModelScope.launch { settingsStore.setContactsPolicy(p) }
    }

    /** Cache size limit in MB (`0` = unlimited). */
    val cacheMaxMb: StateFlow<Int> = settingsStore.cacheMaxMb
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_CACHE_MAX_MB)

    fun setCacheMaxMb(mb: Int) {
        viewModelScope.launch { settingsStore.setCacheMaxMb(mb) }
    }

    /** Whether prefetch is restricted to unmetered (Wi-Fi) networks. */
    val prefetchWifiOnly: StateFlow<Boolean> = settingsStore.prefetchWifiOnly
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setPrefetchWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setPrefetchWifiOnly(enabled) }
    }

    /** Whether prefetch is restricted to when the device is charging. */
    val prefetchChargingOnly: StateFlow<Boolean> = settingsStore.prefetchChargingOnly
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setPrefetchChargingOnly(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setPrefetchChargingOnly(enabled) }
    }

    /** Whether opening a link shows the app chooser ("ask which browser"); default on. */
    val linkChooserEnabled: StateFlow<Boolean> = settingsStore.linkChooserEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setLinkChooserEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setLinkChooserEnabled(enabled) }
    }

    /** Manual "Prefetch now"; the shared [OpProgressOverlay] shows PREFETCH progress. */
    fun prefetchNow() = prefetcher.prefetchNow()

    /** Reason surfaced by a manual prefetch (e.g. `"constraints"`) for the UI snackbar. */
    val prefetchMessage: StateFlow<String?> = prefetcher.message

    fun clearPrefetchMessage() = prefetcher.clearMessage()

    /** Total on-disk size of both offline caches, refreshed on demand + after a clear. */
    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    /** Recompute the cache-size line off the main thread (disk I/O). */
    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSizeBytes.value =
                withContext(Dispatchers.IO) { storeCache.sizeBytes() + blobCache.sizeBytes() }
        }
    }

    /** Wipe both offline disk caches, then refresh the size line. */
    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                storeCache.clear()
                blobCache.clear()
            }
            refreshCacheSize()
        }
    }

    /** Wipe the in-memory session/vault so the app falls back to the unlock screen. */
    fun lockNow() {
        vaultKeyHolder.wipe()
        sessionHolder.clear()
        workspaceCache.clear()
    }

    /**
     * Revoke the current token server-side (best-effort), then clear all local state.
     * Network failure is ignored — the local clear always happens so the device is
     * disconnected regardless.
     */
    suspend fun disconnect() {
        val session = sessionHolder.get()
        if (session != null) {
            runCatching {
                val api = NetworkFactory.create(session.baseUrl, { session.token }, session.spkiPin)
                api.deleteSession()
            }
        }
        runCatching { sessionStore.clear() }
        runCatching { keystoreSealer.clear() }
        vaultKeyHolder.wipe()
        sessionHolder.clear()
        workspaceCache.clear()
        // Drop the offline ciphertext cache too — this device is being unpaired.
        withContext(Dispatchers.IO) {
            runCatching { storeCache.clear() }
            runCatching { blobCache.clear() }
        }
    }
}
