package de.ledgerline.app.data

import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.usecase.LoadGallery
import javax.inject.Inject

class LoadGalleryImpl @Inject constructor(
    private val repo: GalleryRepository,
    private val cache: GalleryCache,
) : LoadGallery {
    override suspend fun invoke(): Outcome<Gallery> {
        val res = repo.load()
        if (res is Outcome.Ok) cache.set(res.value)
        return res
    }
}
