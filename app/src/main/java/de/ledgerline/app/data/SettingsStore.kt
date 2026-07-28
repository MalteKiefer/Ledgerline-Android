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
class SettingsStore(private val context: Context) : de.ledgerline.app.core.prefs.DisplayPrefsSink {
    private val timeoutKey = intPreferencesKey("idle_timeout_minutes")
    private val backgroundRefreshSecondsKey = intPreferencesKey("background_refresh_seconds")
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
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")
    private val backupEnabledKey = booleanPreferencesKey("backup_enabled")
    private val backupAlbumsKey = stringSetPreferencesKey("backup_album_ids")
    private val keepScreenOnKey = booleanPreferencesKey("keep_screen_on")
    private val keepScreenOnMinutesKey = intPreferencesKey("keep_screen_on_minutes")
    private val rememberVaultKey = booleanPreferencesKey("remember_vault")
    private val rememberVaultDaysKey = intPreferencesKey("remember_vault_days")
    private val mapTilesKey = booleanPreferencesKey("map_tiles_enabled")
    private val unitSystemKey = stringPreferencesKey("unit_system")
    private val coordFormatKey = stringPreferencesKey("coord_format")
    private val worldMapOfferedKey = booleanPreferencesKey("world_map_offered")
    private val terrainKey = booleanPreferencesKey("map_terrain_enabled")
    private val duressThresholdKey = intPreferencesKey("duress_threshold")
    // Global display preferences (units + clock), server-synced via /me + POST /preferences.
    private val prefDistanceKey = stringPreferencesKey("pref_distance")
    private val prefElevationKey = stringPreferencesKey("pref_elevation")
    private val prefWeightKey = stringPreferencesKey("pref_weight")
    private val prefTempKey = stringPreferencesKey("pref_temp")
    private val prefGlucoseKey = stringPreferencesKey("pref_glucose")
    private val prefTimeFormatKey = stringPreferencesKey("pref_time_format")

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
     * Automatic background-refresh cadence in **seconds** (while the app is alive), matching the
     * iOS Passwords refresh-interval picker. Governs the [BackgroundSync] loop for the whole
     * offline cache. `0` = off (manual pull-to-refresh only). Default 300 (5 min).
     */
    val backgroundRefreshSeconds: Flow<Int> =
        context.settingsDataStore.data.map { it[backgroundRefreshSecondsKey] ?: DEFAULT_BACKGROUND_REFRESH_SECONDS }

    suspend fun setBackgroundRefreshSeconds(seconds: Int) {
        context.settingsDataStore.edit { it[backgroundRefreshSecondsKey] = seconds }
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

    /**
     * Duress auto-wipe threshold (consecutive wrong passphrases → wipe). Always active;
     * any out-of-range value resolves to [WipePolicy.defaultThreshold]. Defaults to 10.
     */
    val duressThreshold: Flow<Int> =
        context.settingsDataStore.data.map {
            de.ledgerline.app.core.security.WipePolicy.effectiveThreshold(it[duressThresholdKey] ?: de.ledgerline.app.core.security.WipePolicy.defaultThreshold)
        }

    suspend fun setDuressThreshold(n: Int) {
        context.settingsDataStore.edit { it[duressThresholdKey] = de.ledgerline.app.core.security.WipePolicy.effectiveThreshold(n) }
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

    /** App theme mode. Defaults to [ThemeMode.SYSTEM] (follows the device light/dark setting). */
    val themeMode: Flow<ThemeMode> =
        context.settingsDataStore.data.map {
            runCatching { ThemeMode.valueOf(it[themeModeKey] ?: "") }.getOrDefault(ThemeMode.SYSTEM)
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[themeModeKey] = mode.name }
    }

    /** Measurement units for the Explore/Tracker feature. Defaults to metric. */
    val unitSystem: Flow<de.ledgerline.app.core.units.UnitSystem> =
        context.settingsDataStore.data.map {
            runCatching { de.ledgerline.app.core.units.UnitSystem.valueOf(it[unitSystemKey] ?: "") }
                .getOrDefault(de.ledgerline.app.core.units.UnitSystem.METRIC)
        }

    suspend fun setUnitSystem(u: de.ledgerline.app.core.units.UnitSystem) {
        context.settingsDataStore.edit { it[unitSystemKey] = u.name }
    }

    /**
     * Global display preferences (distance/elevation/weight/temp/glucose units + 12/24h clock).
     * Server-synced. Distance/elevation seed from the legacy single `unit_system` toggle when the
     * new keys are absent, so a user who chose imperial for Explore isn't silently reset.
     */
    val displayPrefs: Flow<de.ledgerline.app.core.prefs.DisplayPrefs> =
        context.settingsDataStore.data.map { p ->
            val legacyImperial = p[unitSystemKey] == de.ledgerline.app.core.units.UnitSystem.IMPERIAL.name
            de.ledgerline.app.core.prefs.DisplayPrefs(
                distance = p[prefDistanceKey] ?: if (legacyImperial) "mi" else "km",
                elevation = p[prefElevationKey] ?: if (legacyImperial) "ft" else "m",
                weight = p[prefWeightKey] ?: "kg",
                temp = p[prefTempKey] ?: "c",
                glucose = p[prefGlucoseKey] ?: "mgdl",
                timeFormat = p[prefTimeFormatKey] ?: "24h",
            )
        }

    override suspend fun setDisplayPrefs(prefs: de.ledgerline.app.core.prefs.DisplayPrefs) {
        context.settingsDataStore.edit {
            it[prefDistanceKey] = prefs.distance
            it[prefElevationKey] = prefs.elevation
            it[prefWeightKey] = prefs.weight
            it[prefTempKey] = prefs.temp
            it[prefGlucoseKey] = prefs.glucose
            it[prefTimeFormatKey] = prefs.timeFormat
        }
    }

    /** Coordinate display format for the map/track detail. Defaults to decimal degrees. */
    val coordinateFormat: Flow<de.ledgerline.app.core.geo.CoordinateFormat> =
        context.settingsDataStore.data.map {
            runCatching { de.ledgerline.app.core.geo.CoordinateFormat.valueOf(it[coordFormatKey] ?: "") }
                .getOrDefault(de.ledgerline.app.core.geo.CoordinateFormat.DD)
        }

    suspend fun setCoordinateFormat(f: de.ledgerline.app.core.geo.CoordinateFormat) {
        context.settingsDataStore.edit { it[coordFormatKey] = f.name }
    }

    /** Terrain relief (hillshading). Off by default (downloads DEM tiles for the viewport). */
    val terrainEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[terrainKey] ?: false }

    suspend fun setTerrainEnabled(on: Boolean) {
        context.settingsDataStore.edit { it[terrainKey] = on }
    }

    /** Whether the one-time "download the world base map" offer has been shown. */
    val worldMapOffered: Flow<Boolean> =
        context.settingsDataStore.data.map { it[worldMapOfferedKey] ?: false }

    suspend fun setWorldMapOffered(v: Boolean) {
        context.settingsDataStore.edit { it[worldMapOfferedKey] = v }
    }

    /** Material-You dynamic (wallpaper) color. Defaults to OFF — the hand-authored brand palette. */
    val dynamicColor: Flow<Boolean> =
        context.settingsDataStore.data.map { it[dynamicColorKey] ?: false }

    suspend fun setDynamicColor(on: Boolean) {
        context.settingsDataStore.edit { it[dynamicColorKey] = on }
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

    /**
     * Whether the screen is kept awake while the app is in the foreground (display-only;
     * does NOT affect the idle auto-lock, which still wipes the VK). Defaults to OFF.
     */
    val keepScreenOn: Flow<Boolean> =
        context.settingsDataStore.data.map { it[keepScreenOnKey] ?: false }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.settingsDataStore.edit { it[keepScreenOnKey] = enabled }
    }

    /**
     * How long (minutes) the screen stays awake after the last interaction while
     * [keepScreenOn] is on; `0` = unlimited (stays awake as long as the app is open).
     * Defaults to [DEFAULT_KEEP_SCREEN_ON_MINUTES].
     */
    val keepScreenOnMinutes: Flow<Int> =
        context.settingsDataStore.data.map { it[keepScreenOnMinutesKey] ?: DEFAULT_KEEP_SCREEN_ON_MINUTES }

    suspend fun setKeepScreenOnMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[keepScreenOnMinutesKey] = minutes }
    }

    /**
     * Whether the Vault Key may be persisted (biometric-sealed) so the vault unlocks
     * without re-entering the passphrase. Opt-in, biometrics-only. Defaults to OFF.
     */
    val rememberVaultEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[rememberVaultKey] ?: false }

    suspend fun setRememberVaultEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[rememberVaultKey] = enabled }
    }

    /**
     * After how many days since the last passphrase entry the passphrase is required
     * again. Defaults to [DEFAULT_REMEMBER_VAULT_DAYS].
     */
    val rememberVaultDays: Flow<Int> =
        context.settingsDataStore.data.map { it[rememberVaultDaysKey] ?: DEFAULT_REMEMBER_VAULT_DAYS }

    suspend fun setRememberVaultDays(days: Int) {
        context.settingsDataStore.edit { it[rememberVaultDaysKey] = days }
    }

    /**
     * Whether map tiles may be fetched from the third-party OpenStreetMap tile server.
     * OFF by default: rendering a geotagged photo's location would otherwise leak the
     * (private) coordinates + timing to a third party. When off, map views show a
     * placeholder with an explicit "load map" opt-in.
     */
    val mapTilesEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[mapTilesKey] ?: false }

    suspend fun setMapTilesEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[mapTilesKey] = enabled }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MINUTES = 5
        const val DEFAULT_BACKGROUND_REFRESH_SECONDS = 300
        /** Selectable background-refresh cadences (seconds); 0 = off. Mirrors iOS. */
        val BACKGROUND_REFRESH_OPTIONS = listOf(0, 60, 300, 900, 1800, 3600, 10800, 43200, 86400)
        val TIMEOUT_OPTIONS = listOf(1, 5, 10, 30)

        const val DEFAULT_CACHE_MAX_MB = 1024
        val CACHE_MAX_MB_OPTIONS = listOf(512, 1024, 2048, 0)

        const val DEFAULT_KEEP_SCREEN_ON_MINUTES = 15
        // 0 == unlimited (awake while the app is open).
        val KEEP_SCREEN_ON_OPTIONS = listOf(5, 15, 30, 0)

        const val DEFAULT_REMEMBER_VAULT_DAYS = 7
        val REMEMBER_VAULT_DAYS_OPTIONS = listOf(1, 7, 14, 30)

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
