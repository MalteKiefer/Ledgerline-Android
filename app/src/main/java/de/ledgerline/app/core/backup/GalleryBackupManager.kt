package de.ledgerline.app.core.backup

import android.content.ContentResolver
import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.offline.ConstraintChecker
import de.ledgerline.app.core.ops.OpKind
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.backup.BackupItem
import de.ledgerline.app.data.backup.BackupScanner
import de.ledgerline.app.data.backup.BackupStateStore
import de.ledgerline.app.domain.usecase.ImportPhotos
import de.ledgerline.app.domain.usecase.ImportResult
import de.ledgerline.app.domain.usecase.PhotoSource
import kotlinx.coroutines.CoroutineDispatcher
import de.ledgerline.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backs up selected device albums into the encrypted gallery, reusing [ImportPhotos]
 * (sig-dedup + encrypt + /gallery/process + store write). Runs ONLY while unlocked, via
 * an [OperationManager] foreground op — see the design spec for the zero-knowledge limit.
 */
@Singleton
class GalleryBackupManager @VisibleForTesting internal constructor(
    private val scanner: BackupScanner,
    private val importPhotos: ImportPhotos,
    private val state: BackupStateStore,
    private val settings: SettingsStore,
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val constraints: ConstraintChecker,
    private val operationManager: OperationManager,
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        scanner: BackupScanner,
        importPhotos: ImportPhotos,
        state: BackupStateStore,
        settings: SettingsStore,
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        constraints: ConstraintChecker,
        operationManager: OperationManager,
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        scanner, importPhotos, state, settings, sessionHolder, vaultKeyHolder, constraints,
        operationManager, context.contentResolver, Dispatchers.IO, scope,
    )

    private val running = AtomicBoolean(false)

    /** Fire-and-forget trigger — safe to call on unlock and from a button. */
    fun maybeRun() {
        scope.launch { runNow() }
    }

    /** One backup run; gates on every precondition, then hands new items to [ImportPhotos]. */
    @VisibleForTesting
    internal suspend fun runNow() {
        if (!running.compareAndSet(false, true)) return
        try {
            if (!settings.backupEnabled.first()) return
            if (vaultKeyHolder.get() == null) return      // locked → can't encrypt
            if (sessionHolder.get() == null) return        // not paired → can't auth
            val albums = settings.backupAlbumIds.first()
            if (albums.isEmpty()) return
            if (!constraints.wifiConstraintMet(settings.prefetchWifiOnly.first())) return
            if (!constraints.chargingConstraintMet(settings.prefetchChargingOnly.first())) return

            val known = state.backedUpIds()
            val candidates = withContext(ioDispatcher) { scanner.scan(albums) }
                .filter { it.mediaStoreId !in known }
            if (candidates.isEmpty()) return

            // Keep each candidate paired with its source so we can map an [ImportResult]'s
            // failedSources (identity subset) back to the device item that failed.
            val pairs = candidates.map { it to it.toSource() }
            var result: ImportResult? = null
            operationManager.run(OpKind.BACKUP, total = pairs.size) { report ->
                result = importPhotos.invoke(pairs.map { it.second }, report)
            }.join()
            val r = result ?: return

            // Succeeded = every candidate whose source is NOT failed AND NOT queued. Deduped items
            // count as succeeded (already in the index). Queued items (sealed to the durable import
            // queue during a transient error) are neither marked nor deleted — the queue replay (or
            // the next backup scan, sig-deduped) finishes them, so the original must stay on-device.
            val failedSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PhotoSource, Boolean>())
            failedSet.addAll(r.failedSources)
            failedSet.addAll(r.queuedSources)
            val succeeded = pairs.filterNot { failedSet.contains(it.second) }
            if (succeeded.isEmpty()) return

            // Mark per-succeeded (not all-or-nothing) so a partial batch's failures retry next
            // run while the ones that landed don't re-upload; the sig dedup covers any overlap.
            state.mark(succeeded.map { it.first.mediaStoreId }.toSet())

            // Delete-after-backup: queue the succeeded originals' content-URIs. The actual
            // removal needs a scoped-storage consent dialog (this runs headless), so the UI
            // drains the queue via MediaStore.createTrashRequest.
            if (settings.backupDeleteAfter.first()) {
                state.enqueueDelete(succeeded.map { it.first.uri.toString() })
            }
        } finally {
            running.set(false)
        }
    }

    private fun BackupItem.toSource() = PhotoSource(
        name = name,
        mime = mime,
        size = sizeBytes, // known from MediaStore — no need to re-query
        // Throw (not empty bytes) on an unreadable/deleted item so ImportPhotos records it
        // as a failure — which keeps the batch unmarked and retried, never uploads a 0-byte "photo".
        openInput = { resolver.openInputStream(uri) ?: error("cannot open $uri") },
    )
}
