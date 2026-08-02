package de.ledgerline.app.domain.usecase

/**
 * A content source for a gallery import: name + mime + a **re-openable** input stream + [size].
 * Streaming (not a single `ByteArray`) so a multi-GB video never OOMs — [openInput] may be called
 * more than once (dedup signature, then upload, then process). [lat]/[lng] are optional device
 * coordinates for camera-captured photos (picker/share leave them null — the server reads EXIF).
 */
data class PhotoSource(
    val name: String,
    val mime: String,
    val size: Long,
    val openInput: () -> java.io.InputStream,
    val lat: Double? = null,
    val lng: Double? = null,
)

/**
 * Result of an [ImportPhotos] run: number of sources uploaded/deduped vs. failed, plus the
 * sources that failed (read error or upload error) so the caller can offer a retry.
 */
data class ImportResult(
    val done: Int,
    val failed: Int,
    val failedSources: List<PhotoSource> = emptyList(),
    /** True if the server rejected an upload for exceeding the storage quota (HTTP 413) — the
     *  run stops early so it doesn't hammer a full account; the caller can warn the user. */
    val quotaExceeded: Boolean = false,
)

/**
 * Uploads a batch of photos into the gallery index: read bytes, compute the sha-256
 * signature, dedup against the already-known sigs, upload, and append the new entry.
 *
 * This is the pure import loop shared by the Gallery screen and the share target. It
 * does NOT wrap itself in [de.ledgerline.app.core.ops.OperationManager] — the caller
 * owns the operation and passes a [report] reporter so progress feeds the shared
 * overlay + service notification.
 */
interface ImportPhotos {
    suspend fun invoke(sources: List<PhotoSource>, report: (Int, Int) -> Unit): ImportResult
}
