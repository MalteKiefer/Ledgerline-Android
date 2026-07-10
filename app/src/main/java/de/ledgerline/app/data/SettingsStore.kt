package de.ledgerline.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ledgerline_settings")

/**
 * Non-sensitive UI preferences (plaintext DataStore). Never store secrets here —
 * only things like the idle-lock timeout in minutes.
 */
class SettingsStore(private val context: Context) {
    private val timeoutKey = intPreferencesKey("idle_timeout_minutes")
    private val bgOpsKey = booleanPreferencesKey("background_ops_enabled")
    private val offlineKey = booleanPreferencesKey("offline_cache_enabled")
    private val filesBlobsKey = booleanPreferencesKey("offline_files_blobs")
    private val photosBlobsKey = booleanPreferencesKey("offline_photos_blobs")

    /** Idle auto-lock timeout in minutes; defaults to [DEFAULT_TIMEOUT_MINUTES]. */
    val timeoutMinutes: Flow<Int> =
        context.settingsDataStore.data.map { it[timeoutKey] ?: DEFAULT_TIMEOUT_MINUTES }

    suspend fun setTimeoutMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[timeoutKey] = minutes }
    }

    /**
     * Whether long operations (scans, uploads) may keep running after the app is
     * backgrounded — behind a visible foreground-service notification, with the
     * auto-lock wipe deferred until the operation ends. Defaults to ON.
     */
    val backgroundOpsEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[bgOpsKey] ?: true }

    suspend fun setBackgroundOpsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[bgOpsKey] = enabled }
    }

    /**
     * Master offline-cache switch. When on, the app caches the sealed manifest
     * envelopes on disk so already-viewed data stays usable offline (§11).
     * Defaults to ON; the large per-module blob caches below stay off by default.
     */
    val offlineEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[offlineKey] ?: true }

    suspend fun setOfflineEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[offlineKey] = enabled }
    }

    /** Whether file-content blobs are cached on disk for offline read. Defaults to OFF. */
    val filesBlobsOffline: Flow<Boolean> =
        context.settingsDataStore.data.map { it[filesBlobsKey] ?: false }

    suspend fun setFilesBlobsOffline(enabled: Boolean) {
        context.settingsDataStore.edit { it[filesBlobsKey] = enabled }
    }

    /** Whether photo blobs (originals/thumbs/renditions) are cached on disk. Defaults to OFF. */
    val photosBlobsOffline: Flow<Boolean> =
        context.settingsDataStore.data.map { it[photosBlobsKey] ?: false }

    suspend fun setPhotosBlobsOffline(enabled: Boolean) {
        context.settingsDataStore.edit { it[photosBlobsKey] = enabled }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MINUTES = 5
        val TIMEOUT_OPTIONS = listOf(1, 5, 10, 30)
    }
}
