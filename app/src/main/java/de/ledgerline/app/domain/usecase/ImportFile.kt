package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome
import java.io.InputStream

/**
 * Uploads a single file blob and appends its [de.ledgerline.app.domain.model.FileEntry]
 * to the workspace manifest under an explicit [folder]. Shared by the Files screen
 * (folder = current cwd) and the share target (folder = the picked target folder).
 */
interface ImportFile {
    suspend fun invoke(
        name: String,
        mime: String,
        size: Long,
        folder: String?,
        open: () -> InputStream,
    ): Outcome<Unit>
}
