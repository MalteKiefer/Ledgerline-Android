package de.ledgerline.app.domain.usecase

/**
 * Embed a free-text query into a CLIP text vector via `POST /gallery/embed-text`,
 * for the semantic photo search (cosine against cached image embeddings). Returns the
 * embedding, or null on any failure (the caller falls back to metadata-only search,
 * matching the web `_doSearch` try/catch). Identity/token only — no plaintext leaves
 * the device beyond the query text the user typed, exactly as the web does.
 *
 * Implemented by [de.ledgerline.app.data.GalleryBlobRepository].
 */
interface EmbedText {
    suspend fun invoke(query: String): List<Double>?
}
