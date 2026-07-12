package de.ledgerline.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.ledgerline.app.data.offline.ContactBlobPolicy
import de.ledgerline.app.data.offline.FileBlobPolicy
import de.ledgerline.app.data.offline.PhotoBlobPolicy
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
    private val filesPolicyKey = stringPreferencesKey("offline_files_policy")
    private val photosPolicyKey = stringPreferencesKey("offline_photos_policy")
    private val contactsPolicyKey = stringPreferencesKey("offline_contacts_policy")
    private val cacheMaxMbKey = intPreferencesKey("offline_cache_max_mb")
    private val prefetchWifiOnlyKey = booleanPreferencesKey("prefetch_wifi_only")
    private val prefetchChargingOnlyKey = booleanPreferencesKey("prefetch_charging_only")
    private val linkChooserKey = booleanPreferencesKey("link_chooser_enabled")
    private val contactSortKey = stringPreferencesKey("contact_sort")
    private val dateFormatKey = stringPreferencesKey("date_format")
    private val backupEnabledKey = booleanPreferencesKey("backup_enabled")
    private val backupAlbumsKey = stringSetPreferencesKey("backup_album_ids")

    // Legacy 5a boolean keys, retained only so a stored value migrates into the new
    // enum policies when the new key is absent (§C1).
    private val legacyFilesBlobsKey = booleanPreferencesKey("offline_files_blobs")
    private val legacyPhotosBlobsKey = booleanPreferencesKey("offline_photos_blobs")

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

    /**
     * File-content blob caching policy. Defaults to [FileBlobPolicy.ON_DEMAND].
     * Migration (§C1): if the new key is absent but the legacy `offline_files_blobs`
     * boolean is present, `true` → [FileBlobPolicy.ON_DEMAND], `false` → [FileBlobPolicy.OFF].
     */
    val filesPolicy: Flow<FileBlobPolicy> =
        context.settingsDataStore.data.map { decodeFilesPolicy(it[filesPolicyKey], it[legacyFilesBlobsKey]) }

    suspend fun setFilesPolicy(p: FileBlobPolicy) {
        context.settingsDataStore.edit { it[filesPolicyKey] = p.name }
    }

    /**
     * Photo blob caching policy. Defaults to [PhotoBlobPolicy.ON_DEMAND].
     * Migration mirrors [filesPolicy] from the legacy `offline_photos_blobs` boolean.
     */
    val photosPolicy: Flow<PhotoBlobPolicy> =
        context.settingsDataStore.data.map { decodePhotosPolicy(it[photosPolicyKey], it[legacyPhotosBlobsKey]) }

    suspend fun setPhotosPolicy(p: PhotoBlobPolicy) {
        context.settingsDataStore.edit { it[photosPolicyKey] = p.name }
    }

    /** Contact-avatar blob caching policy. Defaults to [ContactBlobPolicy.ON_DEMAND]. */
    val contactsPolicy: Flow<ContactBlobPolicy> =
        context.settingsDataStore.data.map {
            runCatching { ContactBlobPolicy.valueOf(it[contactsPolicyKey] ?: "") }.getOrDefault(ContactBlobPolicy.ON_DEMAND)
        }

    suspend fun setContactsPolicy(p: ContactBlobPolicy) {
        context.settingsDataStore.edit { it[contactsPolicyKey] = p.name }
    }

    /** Cache size limit in MB (`0` = unlimited). Defaults to [DEFAULT_CACHE_MAX_MB]. */
    val cacheMaxMb: Flow<Int> =
        context.settingsDataStore.data.map { it[cacheMaxMbKey] ?: DEFAULT_CACHE_MAX_MB }

    suspend fun setCacheMaxMb(mb: Int) {
        context.settingsDataStore.edit { it[cacheMaxMbKey] = mb }
    }

    /** Whether prefetch is restricted to unmetered (Wi-Fi) networks. Defaults to ON. */
    val prefetchWifiOnly: Flow<Boolean> =
        context.settingsDataStore.data.map { it[prefetchWifiOnlyKey] ?: true }

    suspend fun setPrefetchWifiOnly(enabled: Boolean) {
        context.settingsDataStore.edit { it[prefetchWifiOnlyKey] = enabled }
    }

    /** Whether prefetch is restricted to when the device is charging. Defaults to ON. */
    val prefetchChargingOnly: Flow<Boolean> =
        context.settingsDataStore.data.map { it[prefetchChargingOnlyKey] ?: true }

    suspend fun setPrefetchChargingOnly(enabled: Boolean) {
        context.settingsDataStore.edit { it[prefetchChargingOnlyKey] = enabled }
    }

    /**
     * Whether opening a link shows the Android app chooser ("ask which browser").
     * Defaults to ON (ask), mirroring the web's chooser-first behaviour.
     */
    val linkChooserEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[linkChooserKey] ?: true }

    suspend fun setLinkChooserEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[linkChooserKey] = enabled }
    }

    /** Contact-list sort order. Defaults to [ContactSort.FIRST]. */
    val contactSort: Flow<ContactSort> =
        context.settingsDataStore.data.map {
            runCatching { ContactSort.valueOf(it[contactSortKey] ?: "") }.getOrDefault(ContactSort.FIRST)
        }

    suspend fun setContactSort(sort: ContactSort) {
        context.settingsDataStore.edit { it[contactSortKey] = sort.name }
    }

    /** Date display format. Defaults to [DateFormatPref.SYSTEM] (device locale). */
    val dateFormat: Flow<DateFormatPref> =
        context.settingsDataStore.data.map {
            runCatching { DateFormatPref.valueOf(it[dateFormatKey] ?: "") }.getOrDefault(DateFormatPref.SYSTEM)
        }

    suspend fun setDateFormat(fmt: DateFormatPref) {
        context.settingsDataStore.edit { it[dateFormatKey] = fmt.name }
    }

    /** Master camera-backup switch. Defaults to OFF (opt-in). */
    val backupEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[backupEnabledKey] ?: false }

    suspend fun setBackupEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[backupEnabledKey] = enabled }
    }

    /** MediaStore bucket ids selected for backup. */
    val backupAlbumIds: Flow<Set<String>> =
        context.settingsDataStore.data.map { it[backupAlbumsKey].orEmpty() }

    suspend fun setBackupAlbumIds(ids: Set<String>) {
        context.settingsDataStore.edit { it[backupAlbumsKey] = ids }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MINUTES = 5
        val TIMEOUT_OPTIONS = listOf(1, 5, 10, 30)

        const val DEFAULT_CACHE_MAX_MB = 1024
        val CACHE_MAX_MB_OPTIONS = listOf(512, 1024, 2048, 0)

        /**
         * Decode the stored files policy (§C1). Prefers the new enum name (defaulting
         * to [FileBlobPolicy.ON_DEMAND] on an unknown/empty value); when absent, migrates
         * the legacy boolean (`true` → ON_DEMAND, `false` → OFF, absent → ON_DEMAND).
         */
        fun decodeFilesPolicy(stored: String?, legacyBool: Boolean?): FileBlobPolicy =
            stored?.let { runCatching { FileBlobPolicy.valueOf(it) }.getOrDefault(FileBlobPolicy.ON_DEMAND) }
                ?: when (legacyBool) {
                    true -> FileBlobPolicy.ON_DEMAND
                    false -> FileBlobPolicy.OFF
                    null -> FileBlobPolicy.ON_DEMAND
                }

        /** Decode the stored photos policy; mirrors [decodeFilesPolicy]. */
        fun decodePhotosPolicy(stored: String?, legacyBool: Boolean?): PhotoBlobPolicy =
            stored?.let { runCatching { PhotoBlobPolicy.valueOf(it) }.getOrDefault(PhotoBlobPolicy.ON_DEMAND) }
                ?: when (legacyBool) {
                    true -> PhotoBlobPolicy.ON_DEMAND
                    false -> PhotoBlobPolicy.OFF
                    null -> PhotoBlobPolicy.ON_DEMAND
                }
    }
}
