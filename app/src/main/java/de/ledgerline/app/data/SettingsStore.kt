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

    companion object {
        const val DEFAULT_TIMEOUT_MINUTES = 5
        val TIMEOUT_OPTIONS = listOf(1, 5, 10, 30)
    }
}
