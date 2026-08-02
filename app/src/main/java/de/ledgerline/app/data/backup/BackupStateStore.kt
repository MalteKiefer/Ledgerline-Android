package de.ledgerline.app.data.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    /**
     * Device content-URIs of originals whose photo is committed to the gallery and are
     * queued for removal (the "delete after backup" opt-in). The headless backup can't
     * show the mandatory scoped-storage consent dialog, so it only enqueues here; the UI
     * drains the queue by launching [android.provider.MediaStore.createTrashRequest].
     */
    private val pendingDeleteKey = stringSetPreferencesKey("pending_delete_uris")

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

    /** Live set of content-URIs queued for device deletion (drives the settings badge). */
    val pendingDelete: Flow<Set<String>> =
        context.backupStateStore.data.map { it[pendingDeleteKey].orEmpty() }

    suspend fun pendingDeleteNow(): Set<String> =
        context.backupStateStore.data.first()[pendingDeleteKey].orEmpty()

    suspend fun enqueueDelete(uris: Collection<String>) {
        if (uris.isEmpty()) return
        context.backupStateStore.edit { it[pendingDeleteKey] = it[pendingDeleteKey].orEmpty() + uris }
    }

    /** Remove the given URIs from the queue (call after the OS confirms the trash). */
    suspend fun clearPendingDelete(uris: Collection<String>) {
        if (uris.isEmpty()) return
        context.backupStateStore.edit { it[pendingDeleteKey] = it[pendingDeleteKey].orEmpty() - uris.toSet() }
    }

    suspend fun clear() {
        context.backupStateStore.edit { it.remove(key); it.remove(pendingDeleteKey) }
    }
}
