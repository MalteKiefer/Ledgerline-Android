package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome
import java.io.InputStream

/**
 * Uploads a single file blob and appends its [de.ledgerline.app.domain.model.FileEntry]
 * to the workspace manifest under an explicit [folder]. Shared by the Files screen
 * (folder = current cwd) and the share target (folder = the picked target folder).
 */
interface ImportFile {
    /**
     * @param queue when true (normal path), a file that can't upload now (offline / recoverable error)
     *   is sealed to the durable import queue to retry on reconnect (returns Ok — the entry appears
     *   after the next sync). The replay path passes false so a re-run never re-queues.
     */
    suspend fun invoke(
        name: String,
        mime: String,
        size: Long,
        folder: String?,
        queue: Boolean = true,
        open: () -> InputStream,
    ): Outcome<Unit>
}
