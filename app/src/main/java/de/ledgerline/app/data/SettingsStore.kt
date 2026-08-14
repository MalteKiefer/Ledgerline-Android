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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ledgerline_settings")

/**
 * Non-sensitive UI preferences (plaintext DataStore). Never store secrets here. Trimmed for the
 * finance pivot to: idle-lock timeout, theme + dynamic color, keep-screen-on, and the server-synced
 * display preferences (units + 12/24h clock).
 */
class SettingsStore(private val context: Context) : de.ledgerline.app.core.prefs.DisplayPrefsSink {
    private val timeoutKey = intPreferencesKey("idle_timeout_minutes")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")
    private val keepScreenOnKey = booleanPreferencesKey("keep_screen_on")
    private val keepScreenOnMinutesKey = intPreferencesKey("keep_screen_on_minutes")
    private val prefDistanceKey = stringPreferencesKey("pref_distance")
    private val prefElevationKey = stringPreferencesKey("pref_elevation")
    private val prefWeightKey = stringPreferencesKey("pref_weight")
    private val prefTempKey = stringPreferencesKey("pref_temp")
    private val prefGlucoseKey = stringPreferencesKey("pref_glucose")
    private val prefTimeFormatKey = stringPreferencesKey("pref_time_format")
    private val prefDateFormatKey = stringPreferencesKey("pref_date_format")
    private val prefTimezoneKey = stringPreferencesKey("pref_timezone")
    // ── Gallery camera-roll auto-backup (opt-in) ──
    private val galBackupEnabledKey = booleanPreferencesKey("gallery_backup_enabled")
    private val galBackupVideosKey = booleanPreferencesKey("gallery_backup_videos")
    private val galBackupWifiOnlyKey = booleanPreferencesKey("gallery_backup_wifi_only")
    private val galBackupSinceKey = androidx.datastore.preferences.core.longPreferencesKey("gallery_backup_since")
    private val galBackupDeleteAfterKey = booleanPreferencesKey("gallery_backup_delete_after")
    private val galBackupBackgroundKey = booleanPreferencesKey("gallery_backup_background")
    private val galBackupAlbumIdKey = androidx.datastore.preferences.core.intPreferencesKey("gallery_backup_album_id")
    private val galBackupChargingKey = booleanPreferencesKey("gallery_backup_charging")
    private val galBackupBatteryOkKey = booleanPreferencesKey("gallery_backup_battery_ok")
    private val galBackupIdleKey = booleanPreferencesKey("gallery_backup_idle")
    private val galBackupExcludedBucketsKey = stringSetPreferencesKey("gallery_backup_excluded_buckets")
    // ── Push notifications (UnifiedPush) ──
    private val pushEnabledKey = booleanPreferencesKey("push_enabled")
    private val pushLockscreenContentKey = booleanPreferencesKey("push_lockscreen_content")
    private val pushMutedCategoriesKey = stringSetPreferencesKey("push_muted_categories")
    private val pushEndpointSentKey = stringPreferencesKey("push_endpoint_sent")
    private val pushEndpointPendingKey = stringPreferencesKey("push_endpoint_pending")
    private val pushDistributorKey = stringPreferencesKey("push_distributor")

    /** Idle auto-lock timeout in minutes; defaults to [DEFAULT_TIMEOUT_MINUTES]. */
    val timeoutMinutes: Flow<Int> =
        context.settingsDataStore.data.map { it[timeoutKey] ?: DEFAULT_TIMEOUT_MINUTES }

    suspend fun setTimeoutMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[timeoutKey] = minutes }
    }

    /** App theme mode. Defaults to [ThemeMode.SYSTEM]. */
    val themeMode: Flow<ThemeMode> =
        context.settingsDataStore.data.map {
            runCatching { ThemeMode.valueOf(it[themeModeKey] ?: "") }.getOrDefault(ThemeMode.SYSTEM)
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[themeModeKey] = mode.name }
    }

    /** Material-You dynamic (wallpaper) color. Defaults to OFF (the brand palette). */
    val dynamicColor: Flow<Boolean> =
        context.settingsDataStore.data.map { it[dynamicColorKey] ?: false }

    suspend fun setDynamicColor(on: Boolean) {
        context.settingsDataStore.edit { it[dynamicColorKey] = on }
    }

    /** Keep the screen awake while foregrounded (display-only). Defaults to OFF. */
    val keepScreenOn: Flow<Boolean> =
        context.settingsDataStore.data.map { it[keepScreenOnKey] ?: false }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.settingsDataStore.edit { it[keepScreenOnKey] = enabled }
    }

    val keepScreenOnMinutes: Flow<Int> =
        context.settingsDataStore.data.map { it[keepScreenOnMinutesKey] ?: DEFAULT_KEEP_SCREEN_ON_MINUTES }

    suspend fun setKeepScreenOnMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[keepScreenOnMinutesKey] = minutes }
    }

    /** Global display preferences (units + 12/24h clock), server-synced via /me + POST /preferences. */
    val displayPrefs: Flow<de.ledgerline.app.core.prefs.DisplayPrefs> =
        context.settingsDataStore.data.map { p ->
            de.ledgerline.app.core.prefs.DisplayPrefs(
                distance = p[prefDistanceKey] ?: "km",
                elevation = p[prefElevationKey] ?: "m",
                weight = p[prefWeightKey] ?: "kg",
                temp = p[prefTempKey] ?: "c",
                glucose = p[prefGlucoseKey] ?: "mgdl",
                timeFormat = p[prefTimeFormatKey] ?: "24h",
                dateFormat = p[prefDateFormatKey] ?: "system",
                timezone = p[prefTimezoneKey] ?: "",
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
            it[prefDateFormatKey] = prefs.dateFormat
            it[prefTimezoneKey] = prefs.timezone
        }
    }

    // ── Gallery camera-roll auto-backup ──────────────────────────────────────────
    val galleryBackupEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[galBackupEnabledKey] ?: false }
    val galleryBackupVideos: Flow<Boolean> = context.settingsDataStore.data.map { it[galBackupVideosKey] ?: true }
    val galleryBackupWifiOnly: Flow<Boolean> = context.settingsDataStore.data.map { it[galBackupWifiOnlyKey] ?: true }
    val galleryBackupSince: Flow<Long> = context.settingsDataStore.data.map { it[galBackupSinceKey] ?: 0L }
    suspend fun setGalleryBackupEnabled(on: Boolean) { context.settingsDataStore.edit { it[galBackupEnabledKey] = on } }
    suspend fun setGalleryBackupVideos(on: Boolean) { context.settingsDataStore.edit { it[galBackupVideosKey] = on } }
    suspend fun setGalleryBackupWifiOnly(on: Boolean) { context.settingsDataStore.edit { it[galBackupWifiOnlyKey] = on } }
    suspend fun setGalleryBackupSince(epochSeconds: Long) { context.settingsDataStore.edit { it[galBackupSinceKey] = epochSeconds } }
    val galleryBackupDeleteAfter: Flow<Boolean> = context.settingsDataStore.data.map { it[galBackupDeleteAfterKey] ?: false }
    val galleryBackupBackground: Flow<Boolean> = context.settingsDataStore.data.map { it[galBackupBackgroundKey] ?: false }
    /** Target album id for backed-up photos, or 0 = none. */
    val galleryBackupAlbumId: Flow<Int> = context.settingsDataStore.data.map { it[galBackupAlbumIdKey] ?: 0 }
    suspend fun setGalleryBackupDeleteAfter(on: Boolean) { context.settingsDataStore.edit { it[galBackupDeleteAfterKey] = on } }
    suspend fun setGalleryBackupBackground(on: Boolean) { context.settingsDataStore.edit { it[galBackupBackgroundKey] = on } }
    suspend fun setGalleryBackupAlbumId(id: Int) { context.settingsDataStore.edit { it[galBackupAlbumIdKey] = id } }
    /** Only run backup while charging. */
    val galleryBackupCharging: Flow<Boolean> = context.settingsDataStore.data.map { it[galBackupChargingKey] ?: false }
    /** Skip backup when the battery is low. */
    val galleryBackupBatteryOk: Flow<Boolean> = context.settingsDataStore.data.map { it[galBackupBatteryOkKey] ?: true }
    /** Background runs only while the device is idle. */
    val galleryBackupIdle: Flow<Boolean> = context.settingsDataStore.data.map { it[galBackupIdleKey] ?: false }
    suspend fun setGalleryBackupCharging(on: Boolean) { context.settingsDataStore.edit { it[galBackupChargingKey] = on } }
    suspend fun setGalleryBackupBatteryOk(on: Boolean) { context.settingsDataStore.edit { it[galBackupBatteryOkKey] = on } }
    suspend fun setGalleryBackupIdle(on: Boolean) { context.settingsDataStore.edit { it[galBackupIdleKey] = on } }
    /** Device media folders (bucket ids) EXCLUDED from backup. */
    val galleryBackupExcludedBuckets: Flow<Set<String>> = context.settingsDataStore.data.map { it[galBackupExcludedBucketsKey] ?: emptySet() }
    suspend fun setGalleryBackupBucketExcluded(bucketId: String, excluded: Boolean) {
        context.settingsDataStore.edit { p ->
            val cur = p[galBackupExcludedBucketsKey]?.toMutableSet() ?: mutableSetOf()
            if (excluded) cur.add(bucketId) else cur.remove(bucketId)
            p[galBackupExcludedBucketsKey] = cur
        }
    }

    // ── Push notifications (UnifiedPush) ──────────────────────────────────────────
    // Delivery does not use the biometric-sealed bearer token: the server sends a
    // display-ready payload to the device's endpoint and the app just renders it.

    /** Whether the user has opted into push. Defaults OFF (opt-in). */
    val pushEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[pushEnabledKey] ?: false }

    suspend fun setPushEnabled(on: Boolean) {
        context.settingsDataStore.edit { it[pushEnabledKey] = on }
    }

    suspend fun pushEnabledNow(): Boolean =
        context.settingsDataStore.data.map { it[pushEnabledKey] ?: false }.first()

    /** Show notification body on the lock screen (else title only). Defaults OFF (private). */
    val pushLockscreenContent: Flow<Boolean> =
        context.settingsDataStore.data.map { it[pushLockscreenContentKey] ?: false }

    suspend fun setPushLockscreenContent(on: Boolean) {
        context.settingsDataStore.edit { it[pushLockscreenContentKey] = on }
    }

    /** Categories the user has muted (empty = every category shows). */
    val pushMutedCategories: Flow<Set<String>> =
        context.settingsDataStore.data.map { it[pushMutedCategoriesKey] ?: emptySet() }

    suspend fun setCategoryMuted(category: String, muted: Boolean) {
        context.settingsDataStore.edit { p ->
            val cur = (p[pushMutedCategoriesKey] ?: emptySet()).toMutableSet()
            if (muted) cur.add(category) else cur.remove(category)
            p[pushMutedCategoriesKey] = cur
        }
    }

    /** Blocking read of muted categories for the push service (no coroutine scope there). */
    suspend fun mutedCategoriesNow(): Set<String> =
        context.settingsDataStore.data.map { it[pushMutedCategoriesKey] ?: emptySet() }.first()

    suspend fun lockscreenContentNow(): Boolean =
        context.settingsDataStore.data.map { it[pushLockscreenContentKey] ?: false }.first()

    /** The last endpoint we successfully delivered to the server (dedup re-registration). */
    val pushEndpointSent: Flow<String?> =
        context.settingsDataStore.data.map { it[pushEndpointSentKey] }

    suspend fun endpointSentNow(): String? =
        context.settingsDataStore.data.map { it[pushEndpointSentKey] }.first()

    suspend fun setEndpointSent(url: String?) {
        context.settingsDataStore.edit { if (url == null) it.remove(pushEndpointSentKey) else it[pushEndpointSentKey] = url }
    }

    /** An endpoint received while locked/offline, queued to send on the next unlock. */
    suspend fun endpointPendingNow(): String? =
        context.settingsDataStore.data.map { it[pushEndpointPendingKey] }.first()

    suspend fun setEndpointPending(url: String?) {
        context.settingsDataStore.edit { if (url == null) it.remove(pushEndpointPendingKey) else it[pushEndpointPendingKey] = url }
    }

    /** The distributor package the connector last used (status display). */
    val pushDistributor: Flow<String?> =
        context.settingsDataStore.data.map { it[pushDistributorKey] }

    suspend fun setPushDistributor(pkg: String?) {
        context.settingsDataStore.edit { if (pkg == null) it.remove(pushDistributorKey) else it[pushDistributorKey] = pkg }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MINUTES = 5
        val TIMEOUT_OPTIONS = listOf(1, 5, 10, 30)
        const val DEFAULT_KEEP_SCREEN_ON_MINUTES = 15
        val KEEP_SCREEN_ON_OPTIONS = listOf(5, 15, 30, 0)
    }
}
