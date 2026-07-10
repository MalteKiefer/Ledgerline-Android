package de.ledgerline.app.domain.usecase

/**
 * Gallery blob-storage usage seam ViewModels depend on so they can be faked in unit tests.
 * Returns (used bytes, quota bytes), or null on any failure.
 */
interface GalleryUsage {
    suspend fun invoke(): Pair<Long, Long>?
}
