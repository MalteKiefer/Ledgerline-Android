package de.ledgerline.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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

    companion object {
        const val DEFAULT_TIMEOUT_MINUTES = 5
        val TIMEOUT_OPTIONS = listOf(1, 5, 10, 30)
        const val DEFAULT_KEEP_SCREEN_ON_MINUTES = 15
        val KEEP_SCREEN_ON_OPTIONS = listOf(5, 15, 30, 0)
    }
}
