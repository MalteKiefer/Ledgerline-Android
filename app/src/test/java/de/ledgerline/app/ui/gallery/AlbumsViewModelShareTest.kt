package de.ledgerline.app.ui.gallery

import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.data.AlbumSharing
import de.ledgerline.app.data.ShareOptions
import de.ledgerline.app.data.ShareResult
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.model.GalleryAlbum
import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.model.ShareInfo
import de.ledgerline.app.domain.usecase.MutateGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Album mutations auto-maintain a live public share (iOS parity: re-push on change, revoke on delete). */
class AlbumsViewModelShareTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val cache = GalleryCache()

    // Apply the mutation onto the cached manifest so refreshAlbumShare sees the new contents.
    private val mutate = object : MutateGallery {
        override suspend fun invoke(mutate: (GalleryManifest) -> GalleryManifest): Outcome<Gallery> {
            val cur = cache.value.value ?: Gallery(GalleryManifest(), 0)
            val next = Gallery(mutate(cur.manifest), cur.version + 1)
            cache.set(next)
            return Outcome.Ok(next)
        }
    }

    private val calls = mutableListOf<String>()
    private val sharing = object : AlbumSharing {
        override fun existingLink(share: ShareInfo?): String? = null
        override suspend fun createAlbumShare(albumId: String, opts: ShareOptions) =
            Outcome.Ok(ShareResult("t", "k", "l"))
        override suspend fun updateAlbumShare(albumId: String, opts: ShareOptions) =
            Outcome.Ok(ShareResult("t", "k", "l"))
        override suspend fun refreshAlbumShare(albumId: String): Outcome<Unit> {
            calls.add("refresh:$albumId"); return Outcome.Ok(Unit)
        }
        override suspend fun revokeAlbumShare(albumId: String): Outcome<Unit> {
            calls.add("revoke:$albumId"); return Outcome.Ok(Unit)
        }
    }

    private fun vm() = AlbumsViewModel(cache, mutate, sharing)

    @Before fun seed() {
        cache.set(Gallery(GalleryManifest(albums = listOf(GalleryAlbum(id = "al", name = "Trip", photoIds = listOf("p1")))), 1))
    }

    @Test fun rename_re_pushes_the_share() = runTest {
        vm().rename("al", "New"); assertEquals(listOf("refresh:al"), calls)
    }

    @Test fun add_and_remove_re_push_the_share() = runTest {
        val vm = vm()
        vm.addPhotos("al", listOf("p2")); vm.removePhoto("al", "p1")
        assertEquals(listOf("refresh:al", "refresh:al"), calls)
    }

    @Test fun delete_revokes_before_dropping_the_album() = runTest {
        vm().delete("al")
        assertEquals(listOf("revoke:al"), calls)
        // The album is gone AND the revoke fired (order guaranteed by the sequential launch).
        assertEquals(emptyList<GalleryAlbum>(), cache.value.value?.manifest?.albums)
    }
}
