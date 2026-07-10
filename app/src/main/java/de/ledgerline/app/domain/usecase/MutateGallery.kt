package de.ledgerline.app.domain.usecase

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.model.GalleryManifest

/** A gallery index write expressed as a pure manifest mutation (409-merge-safe). */
interface MutateGallery {
    suspend fun invoke(mutate: (GalleryManifest) -> GalleryManifest): Outcome<Gallery>
}
