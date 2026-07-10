package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.usecase.DownloadFile
import javax.inject.Inject

class DownloadFileImpl @Inject constructor(
    private val repo: FileBlobRepository,
) : DownloadFile {
    override suspend fun invoke(blob: String, encFileKey: String): Outcome<ByteArray> =
        repo.downloadToBytes(blob, encFileKey)
}
