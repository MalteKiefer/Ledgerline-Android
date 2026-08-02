package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.usecase.FileBlobs
import de.ledgerline.app.domain.usecase.ImportFile
import de.ledgerline.app.domain.usecase.MutateWorkspace
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete file import: stream-encrypt + upload the blob, then append a [FileEntry]
 * to the workspace manifest under [folder]. Extracted from the Files ViewModel so the
 * share target reuses the exact same upload + index-append behaviour.
 */
@Singleton
class ImportFileImpl @Inject constructor(
    private val blobRepo: FileBlobs,
    private val mutate: MutateWorkspace,
    private val importQueue: ImportQueue,
    private val connectivity: de.ledgerline.app.core.offline.Connectivity,
    private val vaultKeyHolder: de.ledgerline.app.core.security.VaultKeyHolder,
) : ImportFile {

    override suspend fun invoke(
        name: String,
        mime: String,
        size: Long,
        folder: String?,
        queue: Boolean,
        open: () -> InputStream,
    ): Outcome<Unit> {
        val vk = vaultKeyHolder.get()
        // Offline (unlocked): don't attempt a doomed upload — seal the source to the durable queue.
        if (queue && vk != null && !connectivity.isOnline()) {
            importQueue.enqueueFile(vk, name, mime, size, ContentSig.of(open, size), open, folder)
            return Outcome.Ok(Unit)
        }
        return when (val up = blobRepo.upload(name, mime, size, open)) {
            is Outcome.Ok -> {
                val res = mutate.invoke { m ->
                    m.copy(
                        files = m.files + FileEntry(
                            id = de.ledgerline.app.core.Ids.newId(), // entry id distinct from the blob id (matches the web contract)
                            blob = up.value.id,
                            encFileKey = up.value.encFileKey,
                            name = name,
                            mime = mime,
                            size = size,
                            folder = folder,
                        ),
                    )
                }
                // A recoverable manifest-append failure is already queued by WorkspaceRepository.save
                // (the FileEntry delta) — the blob is uploaded, so we don't re-queue the whole import.
                when (res) {
                    is Outcome.Ok -> Outcome.Ok(Unit)
                    is Outcome.Err -> res
                }
            }
            // A recoverable BLOB-upload failure: seal the source to the queue and retry on reconnect.
            is Outcome.Err ->
                if (queue && vk != null && up.kind in de.ledgerline.app.core.offline.RECOVERABLE_SAVE_ERRORS) {
                    importQueue.enqueueFile(vk, name, mime, size, ContentSig.of(open, size), open, folder)
                    Outcome.Ok(Unit)
                } else {
                    up
                }
        }
    }
}
