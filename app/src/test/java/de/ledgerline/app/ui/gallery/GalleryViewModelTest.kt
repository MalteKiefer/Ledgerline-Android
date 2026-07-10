package de.ledgerline.app.ui.gallery

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.usecase.GalleryBlobs
import de.ledgerline.app.domain.usecase.LoadGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private class FakeBlobs : GalleryBlobs {
    override suspend fun download(ref: String, key: String): Outcome<ByteArray> =
        Outcome.Err(ErrorKind.NETWORK)
}

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun gallery() = Gallery(GalleryManifest(photos = listOf(
        GalleryPhoto(id = "a", thumbRef = "t", thumbKey = "{}", created = "2026-01-01T00:00:00Z"),
        GalleryPhoto(id = "b", thumbRef = "t", thumbKey = "{}", created = "2026-02-01T00:00:00Z"),
        GalleryPhoto(id = "c", trashed = true, created = "2026-03-01T00:00:00Z"),
    )), version = 4)

    @Test fun newest_first_trashed_hidden() = runTest {
        val cache = GalleryCache()
        val load = object : LoadGallery {
            override suspend fun invoke(): Outcome<Gallery> {
                cache.set(gallery())
                return Outcome.Ok(gallery())
            }
        }
        val vm = GalleryViewModel(load, cache, blobs = FakeBlobs(), thumbs = ThumbCache())
        vm.refresh()
        assertEquals(listOf("b", "a"), vm.state.value.photos.map { it.id })
    }
}
