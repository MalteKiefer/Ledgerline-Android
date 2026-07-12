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
import de.ledgerline.app.domain.usecase.PhotoSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    ) : this(
        scanner, importPhotos, state, settings, sessionHolder, vaultKeyHolder, constraints,
        operationManager, context.contentResolver, Dispatchers.IO,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
            operationManager.run(OpKind.BACKUP, total = sources.size) { report ->
                importPhotos.invoke(sources, report)
            }
            // Mark every candidate handed to ImportPhotos (done or deduped both count as
            // backed up; a genuine failure is retried next run — ImportPhotos persists no
            // partial state and the sig-dedup makes a retry idempotent).
            state.mark(candidates.map { it.mediaStoreId }.toSet())
        } finally {
            running.set(false)
        }
    }

    private fun BackupItem.toSource() = PhotoSource(
        name = name,
        mime = mime,
        read = { resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0) },
    )
}
