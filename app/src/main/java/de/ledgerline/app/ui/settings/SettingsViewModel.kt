package de.ledgerline.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.security.IdleLocker
import de.ledgerline.app.core.security.KeystoreSealer
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.SessionStore
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.remote.NetworkFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {

    /** Current idle-lock timeout in minutes, backed by the plaintext settings store. */
    val timeoutMinutes: StateFlow<Int> = settingsStore.timeoutMinutes
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_TIMEOUT_MINUTES)

    fun setTimeoutMinutes(minutes: Int) {
        idleLocker.timeoutMs = minutes * 60_000L
        viewModelScope.launch { settingsStore.setTimeoutMinutes(minutes) }
    }

    /** Whether background operations may keep running after the app is backgrounded. */
    val backgroundOpsEnabled: StateFlow<Boolean> = settingsStore.backgroundOpsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setBackgroundOpsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setBackgroundOpsEnabled(enabled) }
    }

    /** Master offline-cache switch; when off the per-module blob toggles are disabled. */
    val offlineEnabled: StateFlow<Boolean> = settingsStore.offlineEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setOfflineEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setOfflineEnabled(enabled) }
    }

    /** Whether file-content blobs are cached on disk for offline read. */
    val filesBlobsOffline: StateFlow<Boolean> = settingsStore.filesBlobsOffline
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setFilesBlobsOffline(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setFilesBlobsOffline(enabled) }
    }

    /** Whether photo blobs (originals/thumbs/renditions) are cached on disk. */
    val photosBlobsOffline: StateFlow<Boolean> = settingsStore.photosBlobsOffline
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setPhotosBlobsOffline(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setPhotosBlobsOffline(enabled) }
    }

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
