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

            val sources = candidates.map { it.toSource() }
            // OperationManager.run is fire-and-forget (returns a Job); join it so we read
            // the result only AFTER ImportPhotos has actually finished.
            var result: ImportResult? = null
            operationManager.run(OpKind.BACKUP, total = sources.size) { report ->
                result = importPhotos.invoke(sources, report)
            }.join()
            // Mark this batch backed-up ONLY when every item succeeded or deduped
            // (failed == 0). ImportResult reports counts, not which ids failed, so if any
            // item failed we mark nothing and retry the whole batch next run — the ones
            // that already uploaded dedup instantly by sig, so only the failures re-run.
            if (result?.failed == 0) {
                state.mark(candidates.map { it.mediaStoreId }.toSet())
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
