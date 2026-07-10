package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome

/** Download + decrypt a file blob into memory; the seam ViewModels depend on so they can be faked. */
interface DownloadFile {
    suspend fun invoke(blob: String, encFileKey: String): Outcome<ByteArray>
}
