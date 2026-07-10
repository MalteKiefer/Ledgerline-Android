package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.data.UploadedBlob
import de.ledgerline.app.data.remote.dto.ProcessResponse

/**
 * Blob upload + process surface used by [de.ledgerline.app.data.GalleryUploader].
 * Implemented by [de.ledgerline.app.data.GalleryBlobRepository]; a seam so the
 * uploader can be unit-tested without the network stack.
 */
interface GalleryUploadApi {
    /** Encrypt (secretstream + Padmé) and upload [bytes] as a gallery blob. */
    suspend fun uploadBytes(bytes: ByteArray, name: String): Outcome<UploadedBlob>

    /** Send plaintext [bytes] to `/gallery/process` for derived renditions + meta. */
    suspend fun process(bytes: ByteArray, name: String, mime: String): Outcome<ProcessResponse>
}
