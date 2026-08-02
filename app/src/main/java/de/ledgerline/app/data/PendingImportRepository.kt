package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.offline.Connectivity
import de.ledgerline.app.core.offline.SyncableStore
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.domain.usecase.ImportFile
import de.ledgerline.app.domain.usecase.ImportPhotos
import de.ledgerline.app.domain.usecase.PhotoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drains the durable [ImportQueue] on reconnect: for each queued photo/file it re-runs the full
 * upload + index-append pipeline from the sealed source (decrypting on the fly), removing the item
 * once it lands. Runs through the existing [ImportPhotos]/[ImportFile] use-cases with `queue = false`
 * so a still-failing item is retried next pass rather than re-queued. Registered as a [SyncableStore]
 * so [de.ledgerline.app.core.offline.OfflineSyncEngine] drives it alongside the manifest outbox.
 */
@Singleton
class PendingImportRepository @Inject constructor(
    private val queue: ImportQueue,
    private val importPhotos: ImportPhotos,
    private val importFile: ImportFile,
    private val connectivity: Connectivity,
    private val vaultKeyHolder: VaultKeyHolder,
) : SyncableStore {

    override val syncLabel: String = "imports"

    override fun hasPendingWork(): Boolean = queue.hasPending()

    override suspend fun replayPending(): Boolean = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext false
        if (!connectivity.isOnline()) return@withContext false
        val items = queue.pending(vk)
        if (items.isEmpty()) return@withContext true

        var allOk = true
        for (h in items) {
            val ok = try {
                if (h.item.kind == ImportQueue.Kind.PHOTO.name) {
                    val src = PhotoSource(h.item.name, h.item.mime, h.item.size, h.open, h.item.lat, h.item.lng)
                    val r = importPhotos.invoke(listOf(src), { _, _ -> }, queue = false)
                    // Uploaded or deduped (already in the gallery) → clear it. Still-queued means a
                    // fresh recoverable failure — leave it for the next pass.
                    r.failed == 0 && r.queuedSources.isEmpty()
                } else {
                    importFile.invoke(h.item.name, h.item.mime, h.item.size, h.item.folder, queue = false, open = h.open) is Outcome.Ok
                }
            } catch (_: Exception) {
                false
            }
            if (ok) queue.remove(vk, h.item.id) else allOk = false
        }
        allOk
    }
}
