package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome

/** The new sealed-meta ref/key after a re-embed, plus the model the embedding now carries. */
data class Reembed(val metaRef: String, val metaKey: String, val embModel: String?)

interface GalleryBlobs {
    /** Download + decrypt a gallery blob (thumb/medium/original) to bytes. */
    suspend fun download(ref: String, key: String): Outcome<ByteArray>

    /** Release freed gallery blobs (permanent delete), honoring 429 Retry-After backoff. */
    suspend fun deleteBlobs(refs: List<String>)

    /** Re-embed a photo's CLIP search vector via /gallery/analyze and reseal its meta blob
     *  (embedding + embModel only). Returns the new meta ref/key + model, or null on failure. */
    suspend fun reembed(mediumRef: String, mediumKey: String, metaRef: String, metaKey: String): Reembed?
}
