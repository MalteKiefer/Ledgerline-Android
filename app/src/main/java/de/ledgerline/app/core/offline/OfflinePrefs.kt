package de.ledgerline.app.core.offline

import de.ledgerline.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synchronous, always-current view of the offline toggles so repositories can read
 * the flags without suspending on every request. Seeds each value synchronously at
 * construction (`runBlocking { first() }`) then keeps them live via collectors on an
 * internal scope — mirrors how [de.ledgerline.app.core.ops.OperationManager] caches
 * its background-ops flag.
 */
@Singleton
class OfflinePrefs @Inject constructor(settings: SettingsStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var enabled: Boolean = runBlocking { settings.offlineEnabled.first() }

    @Volatile
    private var filesBlobs: Boolean = runBlocking { settings.filesBlobsOffline.first() }

    @Volatile
    private var photosBlobs: Boolean = runBlocking { settings.photosBlobsOffline.first() }

    init {
        scope.launch { settings.offlineEnabled.collect { enabled = it } }
        scope.launch { settings.filesBlobsOffline.collect { filesBlobs = it } }
        scope.launch { settings.photosBlobsOffline.collect { photosBlobs = it } }
    }

    /** Latest value of the master offline-cache switch. */
    fun enabled(): Boolean = enabled

    /** Latest value of the file-contents-offline toggle. */
    fun filesBlobs(): Boolean = filesBlobs

    /** Latest value of the photos-offline toggle. */
    fun photosBlobs(): Boolean = photosBlobs
}
