package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.data.UploadedBlob
import de.ledgerline.app.data.remote.dto.ProcessResponse
import java.io.InputStream

/**
 * Blob upload + process surface used by [de.ledgerline.app.data.GalleryUploader].
 * Implemented by [de.ledgerline.app.data.GalleryBlobRepository]; a seam so the
 * uploader can be unit-tested without the network stack.
 */
interface GalleryUploadApi {
    /** Encrypt (secretstream + Padmé) and upload [bytes] as a gallery blob (small renditions/meta). */
    suspend fun uploadBytes(bytes: ByteArray, name: String): Outcome<UploadedBlob>

    /**
     * Encrypt + upload a large ORIGINAL by **streaming** from [openInput] (constant memory), using
     * the S3-multipart chunked path at/above 64 MiB — so a multi-GB video never OOMs. [size] is the
     * plaintext byte length.
     */
    suspend fun uploadStream(name: String, size: Long, openInput: () -> InputStream): Outcome<UploadedBlob>

    /** Stream plaintext (from [openInput], [size] bytes) to `/gallery/process` for renditions + meta. */
    suspend fun process(name: String, mime: String, size: Long, openInput: () -> InputStream): Outcome<ProcessResponse>
}
