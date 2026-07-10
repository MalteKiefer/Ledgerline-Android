package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome

interface GalleryBlobs {
    /** Download + decrypt a gallery blob (thumb/medium/original) to bytes. */
    suspend fun download(ref: String, key: String): Outcome<ByteArray>
}
