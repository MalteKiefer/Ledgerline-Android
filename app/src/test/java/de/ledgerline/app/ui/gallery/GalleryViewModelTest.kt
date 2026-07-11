package de.ledgerline.app.ui.gallery

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.MetaCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.core.ops.BackgroundOpsSetting
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.core.ops.ServiceController
import de.ledgerline.app.core.security.LockGuard
import de.ledgerline.app.data.GalleryUploader
import de.ledgerline.app.data.ImportPhotosImpl
import de.ledgerline.app.data.UploadedBlob
import de.ledgerline.app.data.remote.dto.ProcessResponse
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.usecase.EmbedText
import de.ledgerline.app.domain.usecase.GalleryBlobs
import de.ledgerline.app.domain.usecase.GalleryUploadApi
import de.ledgerline.app.domain.usecase.GalleryUsage
import de.ledgerline.app.domain.usecase.ImportPhotos
import de.ledgerline.app.domain.usecase.LoadGallery
import de.ledgerline.app.domain.usecase.MutateGallery
import de.ledgerline.app.domain.usecase.PhotoSource
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
    val deleted = mutableListOf<String>()
    override suspend fun download(ref: String, key: String): Outcome<ByteArray> =
        Outcome.Err(ErrorKind.NETWORK)
    override suspend fun deleteBlobs(refs: List<String>) { deleted += refs }
}

private class FakeGalleryUsage : GalleryUsage {
    override suspend fun invoke(): Pair<Long, Long>? = null
}

/** Returns a canned query embedding (or null to force metadata-only search). */
private class FakeEmbedText(private val embedding: List<Double>? = null) : EmbedText {
    override suspend fun invoke(query: String): List<Double>? = embedding
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

/** A real [OperationManager] that runs the op block inline (setting on, no service). */
private fun testOperationManager(): OperationManager {
    val setting = object : BackgroundOpsSetting {
        override val enabledFlow = MutableStateFlow(true)
    }
    val service = object : ServiceController {
        override fun start() {}
        override fun stop() {}
    }
    // Run op coroutines on an eager test dispatcher so they're deterministic and
    // don't outlive the test (avoids the flaky MainDispatcher teardown race).
    return OperationManager(setting, mockk(relaxed = true), service, UnconfinedTestDispatcher())
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
        importPhotos: ImportPhotos = ImportPhotosImpl(cache, uploader, mutate),
        operationManager: OperationManager = testOperationManager(),
        blobs: FakeBlobs = FakeBlobs(),
        embedText: EmbedText = FakeEmbedText(),
    ) = GalleryViewModel(
        load = load,
        cache = cache,
        blobs = blobs,
        thumbs = ThumbCache(),
        galleryUsage = FakeGalleryUsage(),
        importPhotos = importPhotos,
        mutate = mutate,
        lockGuard = LockGuard(),
        vaultKeyHolder = de.ledgerline.app.core.security.VaultKeyHolder(),
        operationManager = operationManager,
        embedText = embedText,
        metaCache = MetaCache(),
    ).apply { ioDispatcher = UnconfinedTestDispatcher() }

    /**
     * Blocks (real time) until no operation is active. The op runs on the manager's own
     * app scope (real [Dispatchers.Default]), not the virtual-time test scheduler, so we
     * poll on wall-clock time rather than the test dispatcher.
     */
    private fun awaitIdle(om: OperationManager) {
        val deadline = System.currentTimeMillis() + 5_000
        while (om.hasActive() && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    /** Wait (wall-clock) until the search coroutine (which hops to a real IO dispatcher) settles. */
    private fun awaitSearch(vm: GalleryViewModel) {
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.searching.value && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

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
        assertEquals(1, vm.trashCount.value)
    }

    @Test fun trash_view_shows_only_trashed() = runTest {
        val cache = GalleryCache().apply { set(gallery()) }
        val vm = makeVm(cache)
        vm.setTrash(true)
        assertTrue(vm.showTrash.value)
        assertEquals(listOf("c"), vm.state.value.photos.map { it.id })
        vm.setTrash(false)
        assertEquals(listOf("b", "a"), vm.state.value.photos.map { it.id })
    }

    @Test fun restore_clears_trashed() = runTest {
        val cache = GalleryCache().apply { set(gallery()) }
        val vm = makeVm(cache)
        vm.restorePhotos(setOf("c"))
        assertTrue(cache.value.value!!.manifest.photos.first { it.id == "c" }.trashed.not())
    }

    @Test fun delete_forever_removes_photo_and_frees_blobs() = runTest {
        val cache = GalleryCache().apply {
            set(Gallery(GalleryManifest(photos = listOf(
                GalleryPhoto(id = "x", trashed = true, originalRef = "o", thumbRef = "t",
                    metaRef = "m", faceCropRefs = listOf("f1", "f2")),
            )), version = 1))
        }
        val blobs = FakeBlobs()
        val vm = makeVm(cache, blobs = blobs)
        vm.deleteForever(setOf("x"))
        assertTrue(cache.value.value!!.manifest.photos.none { it.id == "x" })
        assertEquals(setOf("o", "t", "m", "f1", "f2"), blobs.deleted.toSet())
    }

    @Test fun empty_trash_removes_only_trashed_and_frees_blobs() = runTest {
        val cache = GalleryCache().apply {
            set(Gallery(GalleryManifest(photos = listOf(
                GalleryPhoto(id = "keep", originalRef = "ko"),
                GalleryPhoto(id = "gone", trashed = true, originalRef = "go", thumbRef = "gt"),
            )), version = 1))
        }
        val blobs = FakeBlobs()
        val vm = makeVm(cache, blobs = blobs)
        vm.emptyTrash()
        assertEquals(listOf("keep"), cache.value.value!!.manifest.photos.map { it.id })
        assertEquals(setOf("go", "gt"), blobs.deleted.toSet())
    }

    @Test fun rotate_cycles_0_90_180_270_0() = runTest {
        val cache = GalleryCache().apply { set(gallery()) }
        val vm = makeVm(cache)
        fun rot() = cache.value.value!!.manifest.photos.first { it.id == "a" }.rotation
        assertEquals(0, rot())
        vm.rotatePhoto("a"); assertEquals(90, rot())
        vm.rotatePhoto("a"); assertEquals(180, rot())
        vm.rotatePhoto("a"); assertEquals(270, rot())
        vm.rotatePhoto("a"); assertEquals(0, rot())
    }

    @Test fun flip_h_and_v_toggle() = runTest {
        val cache = GalleryCache().apply { set(gallery()) }
        val vm = makeVm(cache)
        fun p() = cache.value.value!!.manifest.photos.first { it.id == "a" }
        assertTrue(!p().flipH && !p().flipV)
        vm.flipHorizontal("a"); assertTrue(p().flipH)
        vm.flipHorizontal("a"); assertTrue(!p().flipH)
        vm.flipVertical("a"); assertTrue(p().flipV)
        vm.flipVertical("a"); assertTrue(!p().flipV)
    }

    @Test fun toggle_favorite_flips() = runTest {
        val cache = GalleryCache().apply { set(gallery()) }
        val vm = makeVm(cache)
        fun fav() = cache.value.value!!.manifest.photos.first { it.id == "a" }.favorite
        assertTrue(!fav())
        vm.toggleFavorite("a"); assertTrue(fav())
        vm.toggleFavorite("a"); assertTrue(!fav())
    }

    @Test fun favorites_only_filters_grid() = runTest {
        val cache = GalleryCache().apply {
            set(Gallery(GalleryManifest(photos = listOf(
                GalleryPhoto(id = "a", favorite = true, created = "2026-01-01T00:00:00Z"),
                GalleryPhoto(id = "b", created = "2026-02-01T00:00:00Z"),
            )), version = 1))
        }
        val vm = makeVm(cache)
        assertEquals(listOf("b", "a"), vm.state.value.photos.map { it.id })
        vm.toggleFavoritesOnly()
        assertTrue(vm.favoritesOnly.value)
        assertEquals(listOf("a"), vm.state.value.photos.map { it.id })
        vm.toggleFavoritesOnly()
        assertEquals(listOf("b", "a"), vm.state.value.photos.map { it.id })
    }

    @Test fun set_favorite_bulk_marks_all() = runTest {
        val cache = GalleryCache().apply { set(gallery()) }
        val vm = makeVm(cache)
        vm.setFavorite(setOf("a", "b"), true)
        val photos = cache.value.value!!.manifest.photos.associateBy { it.id }
        assertTrue(photos["a"]!!.favorite)
        assertTrue(photos["b"]!!.favorite)
        vm.setFavorite(setOf("a"), false)
        assertTrue(!cache.value.value!!.manifest.photos.first { it.id == "a" }.favorite)
    }

    @Test fun set_date_sets_taken_at_on_ids_only() = runTest {
        val cache = GalleryCache().apply { set(gallery()) }
        val vm = makeVm(cache)
        val iso = "2026-05-04T00:00:00Z"
        vm.setDate(setOf("a", "b"), iso)
        val photos = cache.value.value!!.manifest.photos.associateBy { it.id }
        assertEquals(iso, photos["a"]!!.taken_at)
        assertEquals(iso, photos["b"]!!.taken_at)
        // "c" was not targeted → unchanged (still null).
        assertEquals(null, photos["c"]!!.taken_at)
    }

    @Test fun set_location_sets_lat_lng_on_ids_only() = runTest {
        val cache = GalleryCache().apply { set(gallery()) }
        val vm = makeVm(cache)
        vm.setLocation(setOf("a"), 52.5, 13.4)
        val photos = cache.value.value!!.manifest.photos.associateBy { it.id }
        assertEquals(52.5, photos["a"]!!.lat)
        assertEquals(13.4, photos["a"]!!.lng)
        // Others untouched.
        assertEquals(null, photos["b"]!!.lat)
        assertEquals(null, photos["b"]!!.lng)
    }

    @Test fun set_date_empty_ids_is_noop() = runTest {
        val cache = GalleryCache().apply { set(gallery()) }
        val vm = makeVm(cache)
        val before = cache.value.value!!.version
        vm.setDate(emptySet(), "2026-05-04T00:00:00Z")
        assertEquals(before, cache.value.value!!.version)
    }

    @Test fun geotagged_photos_only_non_trashed_with_lat_and_lng() = runTest {
        val cache = GalleryCache().apply {
            set(Gallery(GalleryManifest(photos = listOf(
                GalleryPhoto(id = "both", lat = 52.5, lng = 13.4),
                GalleryPhoto(id = "lat_only", lat = 52.5, lng = null),
                GalleryPhoto(id = "lng_only", lat = null, lng = 13.4),
                GalleryPhoto(id = "none"),
                GalleryPhoto(id = "trashed", trashed = true, lat = 48.1, lng = 11.5),
                GalleryPhoto(id = "both2", lat = 40.7, lng = -74.0),
            )), version = 1))
        }
        val vm = makeVm(cache)
        assertEquals(setOf("both", "both2"), vm.geotaggedPhotos().map { it.id }.toSet())
    }

    @Test fun search_blank_clears_results() = runTest {
        val cache = GalleryCache().apply { set(gallery()) }
        val vm = makeVm(cache)
        vm.search("   ")
        assertEquals(null, vm.searchResults.value)
    }

    @Test fun search_matches_metadata_name_when_no_embedding() = runTest {
        val cache = GalleryCache().apply {
            set(Gallery(GalleryManifest(photos = listOf(
                GalleryPhoto(id = "beach", name = "Beach sunset.jpg"),
                GalleryPhoto(id = "car", name = "car.jpg", camera = "Pixel 8"),
            )), version = 1))
        }
        // No embedding → embedText returns null → metadata-only fallback (web try/catch).
        val vm = makeVm(cache, embedText = FakeEmbedText(null))
        vm.search("beach"); awaitSearch(vm)
        assertEquals(listOf("beach"), vm.searchResults.value?.map { it.id })
        vm.search("pixel"); awaitSearch(vm)
        assertEquals(listOf("car"), vm.searchResults.value?.map { it.id })
    }

    @Test fun clear_search_resets_state() = runTest {
        val cache = GalleryCache().apply { set(gallery()) }
        val vm = makeVm(cache)
        vm.search("anything")
        vm.clearSearch()
        assertEquals(null, vm.searchResults.value)
        assertTrue(!vm.searching.value)
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
        val om = testOperationManager()
        val vm = makeVm(cache, uploader = uploader, mutate = FakeMutateGallery(cache), operationManager = om)

        val bytes = byteArrayOf(1, 2, 3)
        val source = PhotoSource("a.jpg", "image/jpeg", read = { bytes })

        // First upload — should go through. The op runs on the manager's app scope, so
        // wait for it to drain before asserting.
        vm.uploadAll(listOf(source))
        awaitIdle(om)

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
        awaitIdle(om)

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
