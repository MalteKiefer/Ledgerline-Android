package de.ledgerline.app.ui.gallery

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.core.security.LockGuard
import de.ledgerline.app.data.GalleryUploader
import de.ledgerline.app.data.UploadedBlob
import de.ledgerline.app.data.remote.dto.ProcessResponse
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.usecase.GalleryBlobs
import de.ledgerline.app.domain.usecase.GalleryUploadApi
import de.ledgerline.app.domain.usecase.GalleryUsage
import de.ledgerline.app.domain.usecase.LoadGallery
import de.ledgerline.app.domain.usecase.MutateGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeBlobs : GalleryBlobs {
    override suspend fun download(ref: String, key: String): Outcome<ByteArray> =
        Outcome.Err(ErrorKind.NETWORK)
}

private class FakeGalleryUsage : GalleryUsage {
    override suspend fun invoke(): Pair<Long, Long>? = null
}

// ---------------------------------------------------------------------------
// Fakes for uploadAll tests
// ---------------------------------------------------------------------------

/** Tracks how many times uploadBytes / process are called. Returns canned blobs. */
private class FakeGalleryUploadApi(val processResponse: ProcessResponse) : GalleryUploadApi {
    var uploadCount = 0
    var processCount = 0

    override suspend fun uploadBytes(bytes: ByteArray, name: String): Outcome<UploadedBlob> {
        uploadCount++
        return Outcome.Ok(UploadedBlob(id = "blob-$uploadCount", encFileKey = "{c:\"x\",n:\"y\"}", size = bytes.size.toLong()))
    }

    override suspend fun process(bytes: ByteArray, name: String, mime: String): Outcome<ProcessResponse> {
        processCount++
        return Outcome.Ok(processResponse)
    }
}

/**
 * A [GalleryUploader] that delegates to [FakeGalleryUploadApi] and avoids
 * android.util.Base64 (which throws off-device) by not calling it at all —
 * the fake ProcessResponse has null thumb/medium/faces=empty.
 */
private fun fakeUploader(api: GalleryUploadApi): GalleryUploader = object : GalleryUploader(api) {
    // decodeBase64 is never called when thumb/medium/motion/crops are null — no override needed.
}

/**
 * [MutateGallery] fake that applies the mutation directly to a [GalleryCache]
 * so that subsequent reads from the cache reflect the new photo.
 */
private class FakeMutateGallery(private val cache: GalleryCache) : MutateGallery {
    override suspend fun invoke(mutate: (GalleryManifest) -> GalleryManifest): Outcome<Gallery> {
        val current = cache.value.value?.manifest ?: GalleryManifest()
        val version = cache.value.value?.version ?: 0
        val next = mutate(current)
        val g = Gallery(next, version + 1)
        cache.set(g)
        return Outcome.Ok(g)
    }
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

    private fun makeVm(
        cache: GalleryCache,
        load: LoadGallery = object : LoadGallery {
            override suspend fun invoke(): Outcome<Gallery> = Outcome.Err(ErrorKind.NETWORK)
        },
        uploader: GalleryUploader = fakeUploader(FakeGalleryUploadApi(ProcessResponse())),
        mutate: MutateGallery = FakeMutateGallery(cache),
    ) = GalleryViewModel(
        load = load,
        cache = cache,
        blobs = FakeBlobs(),
        thumbs = ThumbCache(),
        galleryUsage = FakeGalleryUsage(),
        uploader = uploader,
        mutate = mutate,
        lockGuard = LockGuard(),
    )

    @Test fun newest_first_trashed_hidden() = runTest {
        val cache = GalleryCache()
        val load = object : LoadGallery {
            override suspend fun invoke(): Outcome<Gallery> {
                cache.set(gallery())
                return Outcome.Ok(gallery())
            }
        }
        val vm = makeVm(cache, load = load)
        vm.refresh()
        assertEquals(listOf("b", "a"), vm.state.value.photos.map { it.id })
    }

    /**
     * Verifies the upload queue:
     * 1. A single source is uploaded and appended to the gallery cache.
     * 2. A second source with identical bytes is deduped (upload/process not called again).
     */
    @Test fun uploads_and_appends() = runTest {
        val cache = GalleryCache()
        // Seed the cache with an empty gallery so save() has a base.
        cache.set(Gallery(GalleryManifest(), version = 1))

        // ProcessResponse with null thumb/medium and no faces avoids the android.util.Base64 path.
        val processResponse = ProcessResponse(
            thumb = null,
            medium = null,
            faces = emptyList(),
        )
        val fakeApi = FakeGalleryUploadApi(processResponse)
        val uploader = fakeUploader(fakeApi)
        val vm = makeVm(cache, uploader = uploader, mutate = FakeMutateGallery(cache))

        val bytes = byteArrayOf(1, 2, 3)
        val source = PhotoSource("a.jpg", "image/jpeg") { bytes }

        // First upload — should go through.
        vm.uploadAll(listOf(source))

        val photosAfterFirst = cache.value.value?.manifest?.photos.orEmpty()
        assertEquals("Expected exactly one photo after first upload", 1, photosAfterFirst.size)
        // originalRef and metaRef must be set (two uploadBytes calls: original + meta).
        val uploaded = photosAfterFirst[0]
        assertTrue("originalRef should be set", uploaded.originalRef != null)
        assertTrue("metaRef should be set", uploaded.metaRef != null)

        val uploadsAfterFirst = fakeApi.uploadCount
        val processAfterFirst = fakeApi.processCount
        assertTrue("uploadBytes should have been called at least once", uploadsAfterFirst >= 1)
        assertEquals("process should have been called once", 1, processAfterFirst)

        // Second upload with the same bytes — must be deduped (sigs match).
        vm.uploadAll(listOf(source))

        assertEquals(
            "Upload count must not grow after dedup",
            uploadsAfterFirst,
            fakeApi.uploadCount,
        )
        assertEquals(
            "Process count must not grow after dedup",
            processAfterFirst,
            fakeApi.processCount,
        )
        // Gallery still has exactly one photo.
        assertEquals(
            "Gallery must still have one photo after dedup",
            1,
            cache.value.value?.manifest?.photos?.size,
        )
    }
}
