package de.ledgerline.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.Prefetcher
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.security.BiometricAvailability
import de.ledgerline.app.core.security.IdleLocker
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.core.backup.GalleryBackupManager
import de.ledgerline.app.data.AccountRepository
import de.ledgerline.app.data.ContactSort
import de.ledgerline.app.data.DateFormatPref
import de.ledgerline.app.data.RememberedVaultStore
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
    private val rememberedVault: RememberedVaultStore,
    private val biometric: BiometricAvailability,
    private val securityLog: de.ledgerline.app.core.security.SecurityLog,
    private val integrity: de.ledgerline.app.core.integrity.IntegritySignal,
) : ViewModel() {

    /** §3.6 client-integrity report (attestation + root heuristics); null until assessed. */
    private val _integrity = MutableStateFlow<de.ledgerline.app.core.integrity.IntegrityReport?>(null)
    val integrityReport: StateFlow<de.ledgerline.app.core.integrity.IntegrityReport?> = _integrity.asStateFlow()

    init { viewModelScope.launch { _integrity.value = integrity.assess() } }

    /** STRONG biometrics enrolled — gates whether the "remember unlock" toggle is usable. */
    val strongBiometricAvailable: Boolean = biometric.strongEnrolled()

    /** Duress auto-wipe threshold (always active; one of [WipePolicy.options]). */
    val duressThreshold: StateFlow<Int> = settingsStore.duressThreshold
        .stateIn(viewModelScope, SharingStarted.Eagerly, de.ledgerline.app.core.security.WipePolicy.defaultThreshold)

    fun setDuressThreshold(n: Int) { viewModelScope.launch { settingsStore.setDuressThreshold(n) } }

    /** The encrypted security audit log (newest last). */
    val securityEvents: StateFlow<List<de.ledgerline.app.core.security.SecurityLogEntry>> = securityLog.entries

    init { viewModelScope.launch { securityLog.ensureLoaded() } }

    fun clearSecurityLog() { viewModelScope.launch { securityLog.clear() } }

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

    /** App theme (System/Light/Dark) + Material-You dynamic-color opt-in. */
    val themeMode: StateFlow<de.ledgerline.app.data.ThemeMode> = settingsStore.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, de.ledgerline.app.data.ThemeMode.SYSTEM)
    fun setThemeMode(mode: de.ledgerline.app.data.ThemeMode) { viewModelScope.launch { settingsStore.setThemeMode(mode) } }

    val dynamicColor: StateFlow<Boolean> = settingsStore.dynamicColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setDynamicColor(on: Boolean) { viewModelScope.launch { settingsStore.setDynamicColor(on) } }

    /** Automatic background-refresh cadence in seconds (0 = off). Drives the BackgroundSync loop. */
    val backgroundRefreshSeconds: StateFlow<Int> = settingsStore.backgroundRefreshSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_BACKGROUND_REFRESH_SECONDS)

    fun setBackgroundRefreshSeconds(seconds: Int) {
        viewModelScope.launch { settingsStore.setBackgroundRefreshSeconds(seconds) }
    }

    /** Whether the Vault Key may be biometric-persisted so unlock skips the passphrase. */
    val rememberVaultEnabled: StateFlow<Boolean> = settingsStore.rememberVaultEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setRememberVaultEnabled(on: Boolean) {
        viewModelScope.launch {
            settingsStore.setRememberVaultEnabled(on)
            // Disarm immediately when turned off: drop the sealed blob + the Keystore key.
            if (!on) rememberedVault.clear()
        }
    }

    /** After how many days since the last passphrase entry the passphrase is required again. */
    val rememberVaultDays: StateFlow<Int> = settingsStore.rememberVaultDays
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_REMEMBER_VAULT_DAYS)

    fun setRememberVaultDays(days: Int) {
        viewModelScope.launch { settingsStore.setRememberVaultDays(days) }
    }

    /** Whether map tiles may be fetched from OpenStreetMap (privacy; default off). */
    val mapTilesEnabled: StateFlow<Boolean> = settingsStore.mapTilesEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setMapTilesEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setMapTilesEnabled(enabled) }
    }

    /** Whether the screen is kept awake while the app is in the foreground (display-only). */
    val keepScreenOn: StateFlow<Boolean> = settingsStore.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setKeepScreenOn(enabled) }
    }

    /** How long the screen stays awake after the last interaction (`0` = unlimited). */
    val keepScreenOnMinutes: StateFlow<Int> = settingsStore.keepScreenOnMinutes
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_KEEP_SCREEN_ON_MINUTES)

    fun setKeepScreenOnMinutes(minutes: Int) {
        viewModelScope.launch { settingsStore.setKeepScreenOnMinutes(minutes) }
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
        runCatching { rememberedVault.clear() }
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
