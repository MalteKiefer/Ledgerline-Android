package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.usecase.MutateGallery
import javax.inject.Inject

class MutateGalleryImpl @Inject constructor(
    private val repo: GalleryRepository,
) : MutateGallery {
    override suspend fun invoke(mutate: (GalleryManifest) -> GalleryManifest): Outcome<Gallery> =
        repo.save(mutate)
}
