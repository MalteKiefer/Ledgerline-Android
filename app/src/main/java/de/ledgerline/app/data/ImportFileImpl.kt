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
) : ImportFile {

    override suspend fun invoke(
        name: String,
        mime: String,
        size: Long,
        folder: String?,
        open: () -> InputStream,
    ): Outcome<Unit> = when (val up = blobRepo.upload(name, mime, size, open)) {
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
            when (res) {
                is Outcome.Ok -> Outcome.Ok(Unit)
                is Outcome.Err -> res
            }
        }
        is Outcome.Err -> up
    }
}
