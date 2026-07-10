package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.data.UploadedBlob
import java.io.InputStream

/** Stream-encrypt + upload a file blob; the seam ViewModels depend on so they can be faked. */
interface UploadFile {
    suspend fun invoke(name: String, mime: String, size: Long, open: () -> InputStream): Outcome<UploadedBlob>
}
