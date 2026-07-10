package de.ledgerline.app.domain.usecase

/**
 * Files blob-storage usage seam ViewModels depend on so they can be faked in unit tests.
 * Returns (used bytes, quota bytes), or null on any failure.
 */
interface FilesUsage {
    suspend fun invoke(): Pair<Long, Long>?
}
