package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.data.UploadedBlob
import java.io.InputStream

/**
 * The blob-moving seam ViewModels depend on so they can be faked in unit tests.
 *
 * Mirrors the concrete [de.ledgerline.app.data.FileBlobRepository] surface the
 * Files ViewModel needs: stream-encrypt upload, download-to-memory (viewer),
 * streamed download (SAF export), and throttled blob deletion.
 */
interface FileBlobs {
    suspend fun upload(name: String, mime: String, size: Long, openInput: () -> InputStream): Outcome<UploadedBlob>
    suspend fun downloadToBytes(blob: String, encFileKey: String): Outcome<ByteArray>
    suspend fun downloadTo(blob: String, encFileKey: String, write: (ByteArray) -> Unit): Outcome<Unit>
    suspend fun deleteBlobs(blobs: List<String>)

    /**
     * Living-set reconcile: hand the server EVERY blob id still referenced by the files manifest so
     * it reclaims orphans a failed eager DELETE left behind (24 h grace). Best-effort; ignores
     * failures. [livingSet] must be the COMPLETE referenced set — a missing id would free live data.
     */
    suspend fun reconcile(livingSet: List<String>)
}
