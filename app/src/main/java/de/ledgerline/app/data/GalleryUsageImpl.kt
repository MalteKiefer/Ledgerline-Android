package de.ledgerline.app.data

import de.ledgerline.app.domain.usecase.GalleryUsage
import javax.inject.Inject

/** Delegates to [GalleryRepository.galleryUsage]. */
class GalleryUsageImpl @Inject constructor(private val repo: GalleryRepository) : GalleryUsage {
    override suspend fun invoke(): Pair<Long, Long>? = repo.galleryUsage()
}
