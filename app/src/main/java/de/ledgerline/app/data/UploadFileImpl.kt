package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.usecase.UploadFile
import java.io.InputStream
import javax.inject.Inject

class UploadFileImpl @Inject constructor(
    private val repo: FileBlobRepository,
) : UploadFile {
    override suspend fun invoke(name: String, mime: String, size: Long, open: () -> InputStream): Outcome<UploadedBlob> =
        repo.upload(name, mime, size, open)
}
