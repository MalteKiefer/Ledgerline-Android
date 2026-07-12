package de.ledgerline.app.data.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backupStateStore: DataStore<Preferences> by preferencesDataStore(name = "ledgerline_backup_state")

/**
 * Device-local set of already-backed-up MediaStore ids — a fast-skip so a run doesn't
 * re-read + re-hash every device photo. Not sensitive (ids are device-local); the real
 * dedup is the sha-256 sig check inside [de.ledgerline.app.domain.usecase.ImportPhotos].
 */
@Singleton
class BackupStateStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val key = stringSetPreferencesKey("backed_up_ids")

    suspend fun backedUpIds(): Set<Long> =
        context.backupStateStore.data.first()[key].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    suspend fun isBackedUp(id: Long): Boolean =
        context.backupStateStore.data.first()[key].orEmpty().contains(id.toString())

    suspend fun mark(ids: Set<Long>) {
        if (ids.isEmpty()) return
        context.backupStateStore.edit { prefs ->
            prefs[key] = prefs[key].orEmpty() + ids.map { it.toString() }
        }
    }

    suspend fun clear() {
        context.backupStateStore.edit { it.remove(key) }
    }
}
