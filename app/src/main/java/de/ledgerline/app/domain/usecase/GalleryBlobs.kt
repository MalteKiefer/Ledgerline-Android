package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome

interface GalleryBlobs {
    /** Download + decrypt a gallery blob (thumb/medium/original) to bytes. */
    suspend fun download(ref: String, key: String): Outcome<ByteArray>

    /** Release freed gallery blobs (permanent delete), honoring 429 Retry-After backoff. */
    suspend fun deleteBlobs(refs: List<String>)
}
