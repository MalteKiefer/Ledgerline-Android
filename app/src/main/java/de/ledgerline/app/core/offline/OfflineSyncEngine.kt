package de.ledgerline.app.core.offline

import de.ledgerline.app.core.security.VaultKeyHolder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drains the offline write [SyncOutbox]: on reconnect (and after every successful save while online)
 * it asks each [SyncableStore] to replay its pending record deltas onto the live server manifest.
 * Serialized by a mutex so overlapping triggers (connectivity callback + background tick + a manual
 * save) never double-push. Requires an unlocked vault (the deltas are VK-sealed); a no-op otherwise.
 */
@Singleton
class OfflineSyncEngine @Inject constructor(
    private val stores: Set<@JvmSuppressWildcards SyncableStore>,
    private val outbox: SyncOutbox,
    private val connectivity: Connectivity,
    private val vaultKeyHolder: VaultKeyHolder,
) {
    private val mutex = Mutex()

    /** True when there is at least one pending offline write waiting to sync (manifest outbox OR a
     *  store's own out-of-band queue, e.g. pending blob imports). */
    fun hasPending(): Boolean = outbox.hasPending() || stores.any { it.hasPendingWork() }

    /**
     * Replay every store's pending deltas. Returns true when the outbox is fully drained. Safe to
     * call often; skips work when locked, offline, or nothing is pending.
     */
    suspend fun syncNow(): Boolean = mutex.withLock {
        if (vaultKeyHolder.get() == null) return false
        if (!connectivity.isOnline()) return false
        if (!hasPending()) return true
        var allClear = true
        for (store in stores) {
            allClear = try {
                store.replayPending() && allClear
            } catch (_: Exception) {
                false // one store failing must not abort the others
            }
        }
        allClear && !outbox.hasPending()
    }
}
