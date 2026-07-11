package de.ledgerline.app.core.offline

import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.ops.BackgroundOpsSetting
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.core.ops.ServiceController
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.FakeOfflineFlags
import de.ledgerline.app.data.FileBlobRepository
import de.ledgerline.app.data.GalleryBlobRepository
import de.ledgerline.app.data.NotImplementedApi
import de.ledgerline.app.data.SealTagCrypto
import de.ledgerline.app.data.offline.FileBlobPolicy
import de.ledgerline.app.data.offline.PhotoBlobPolicy
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.Gallery
import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.File

class PrefetcherTest {

    /** Records the refs fetched via each endpoint; returns a trivial 200 ciphertext. */
    private class RecordingApi : NotImplementedApi() {
        val galleryRefs = mutableListOf<String>()
        val fileRefs = mutableListOf<String>()
        override suspend fun galleryRaw(blob: String): Response<ResponseBody> {
            galleryRefs.add(blob)
            return Response.success(byteArrayOf(1, 2, 3).toResponseBody("application/octet-stream".toMediaType()))
        }
        override suspend fun rawFile(blob: String): Response<ResponseBody> {
            fileRefs.add(blob)
            return Response.success(byteArrayOf(4, 5, 6).toResponseBody("application/octet-stream".toMediaType()))
        }
    }

    private class FakeConstraints(
        private val wifi: Boolean = true,
        private val charging: Boolean = true,
    ) : Constraints {
        override fun wifiConstraintMet(wifiOnly: Boolean) = wifi
        override fun chargingConstraintMet(chargingOnly: Boolean) = charging
    }

    private fun tmpCache() = BlobDiskCache(File(System.getProperty("java.io.tmpdir"), "t-pf-" + System.nanoTime()))

    /** A real [OperationManager] that runs the op block inline (setting on, no service). */
    private fun opManager(): OperationManager {
        val setting = object : BackgroundOpsSetting { override val enabledFlow = MutableStateFlow(true) }
        val service = object : ServiceController { override fun start() {}; override fun stop() {} }
        return OperationManager(setting, mockk(relaxed = true), service)
    }

    private fun galleryRepo(api: LedgerlineApi, cache: BlobDiskCache): GalleryBlobRepository {
        val sh = SessionHolder().apply { set(Session("https://x", "tok", "", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        return GalleryBlobRepository.forTest(sh, vh, SealTagCrypto(), cache, FakeOfflineFlags(), api)
    }

    private fun fileRepo(api: LedgerlineApi, cache: BlobDiskCache): FileBlobRepository {
        val sh = SessionHolder().apply { set(Session("https://x", "tok", "", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        return FileBlobRepository(sh, vh, SealTagCrypto(), cache, FakeOfflineFlags(), File(System.getProperty("java.io.tmpdir")), apiProvider = { api })
    }

    private fun prefetcher(
        flags: OfflineFlags,
        constraints: Constraints,
        api: RecordingApi,
        cache: BlobDiskCache,
        gallery: Gallery? = null,
        workspace: Workspace? = null,
    ): Prefetcher {
        val gc = GalleryCache().apply { gallery?.let { set(it) } }
        val wc = WorkspaceCache().apply { workspace?.let { set(it) } }
        return Prefetcher(gc, wc, galleryRepo(api, cache), fileRepo(api, cache), cache, flags, constraints, opManager())
    }

    private fun awaitIdle() = Thread.sleep(200)

    private fun photo(id: String, trashed: Boolean = false) = GalleryPhoto(
        id = id, thumbRef = "$id-thumb", mediumRef = "$id-medium", originalRef = "$id-orig",
        motionRef = "$id-motion", metaRef = "$id-meta", faceCropRefs = listOf("$id-face"), trashed = trashed,
    )

    private fun gallery(vararg photos: GalleryPhoto) = Gallery(GalleryManifest(photos = photos.toList()), version = 1)

    private fun workspace(vararg files: FileEntry) = Workspace(WorkspaceManifest(files = files.toList()), version = 1)

    @Test
    fun photos_thumbs_enumerates_only_thumb_refs() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(photosPolicy = PhotoBlobPolicy.THUMBS, filesPolicy = FileBlobPolicy.OFF),
            FakeConstraints(), api, cache, gallery = gallery(photo("a"), photo("b")),
        )
        pf.prefetchNow()
        awaitIdle()
        assertEquals(setOf("a-thumb", "b-thumb"), api.galleryRefs.toSet())
        assertTrue(api.fileRefs.isEmpty())
    }

    @Test
    fun photos_all_enumerates_every_photo_ref() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(photosPolicy = PhotoBlobPolicy.ALL, filesPolicy = FileBlobPolicy.OFF),
            FakeConstraints(), api, cache, gallery = gallery(photo("a")),
        )
        pf.prefetchNow()
        awaitIdle()
        assertEquals(
            setOf("a-thumb", "a-medium", "a-orig", "a-motion", "a-meta", "a-face"),
            api.galleryRefs.toSet(),
        )
    }

    @Test
    fun photos_off_enumerates_nothing() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(photosPolicy = PhotoBlobPolicy.OFF, filesPolicy = FileBlobPolicy.OFF),
            FakeConstraints(), api, cache, gallery = gallery(photo("a")),
        )
        pf.prefetchNow()
        awaitIdle()
        assertTrue(api.galleryRefs.isEmpty())
        assertTrue(api.fileRefs.isEmpty())
    }

    @Test
    fun trashed_photos_are_skipped() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(photosPolicy = PhotoBlobPolicy.THUMBS, filesPolicy = FileBlobPolicy.OFF),
            FakeConstraints(), api, cache, gallery = gallery(photo("a"), photo("b", trashed = true)),
        )
        pf.prefetchNow()
        awaitIdle()
        assertEquals(setOf("a-thumb"), api.galleryRefs.toSet())
    }

    @Test
    fun files_all_enumerates_blobs_else_none() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(photosPolicy = PhotoBlobPolicy.OFF, filesPolicy = FileBlobPolicy.ALL),
            FakeConstraints(), api, cache,
            workspace = workspace(
                FileEntry(id = "f1", blob = "b1"),
                FileEntry(id = "f2", blob = "b2", trashed = true),
            ),
        )
        pf.prefetchNow()
        awaitIdle()
        assertEquals(setOf("b1"), api.fileRefs.toSet())
        assertTrue(api.galleryRefs.isEmpty())
    }

    @Test
    fun files_on_demand_enumerates_nothing() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(photosPolicy = PhotoBlobPolicy.OFF, filesPolicy = FileBlobPolicy.ON_DEMAND),
            FakeConstraints(), api, cache,
            workspace = workspace(FileEntry(id = "f1", blob = "b1")),
        )
        pf.prefetchNow()
        awaitIdle()
        assertTrue(api.fileRefs.isEmpty())
    }

    @Test
    fun already_cached_refs_are_skipped() {
        val api = RecordingApi()
        val cache = tmpCache()
        cache.put("a-thumb", byteArrayOf(9))
        val pf = prefetcher(
            FakeOfflineFlags(photosPolicy = PhotoBlobPolicy.THUMBS, filesPolicy = FileBlobPolicy.OFF),
            FakeConstraints(), api, cache, gallery = gallery(photo("a"), photo("b")),
        )
        pf.prefetchNow()
        awaitIdle()
        // a-thumb already cached → not refetched; only b-thumb hits the network.
        assertEquals(setOf("b-thumb"), api.galleryRefs.toSet())
    }

    @Test
    fun constraint_failure_prefetches_nothing_and_sets_message_on_manual() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(photosPolicy = PhotoBlobPolicy.THUMBS, filesPolicy = FileBlobPolicy.OFF, wifiOnly = true),
            FakeConstraints(wifi = false), api, cache, gallery = gallery(photo("a")),
        )
        pf.prefetchNow()
        awaitIdle()
        assertTrue(api.galleryRefs.isEmpty())
        assertEquals("constraints", pf.message.value)
        pf.clearMessage()
        assertNull(pf.message.value)
    }

    @Test
    fun auto_with_no_prefetch_policy_runs_nothing() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(photosPolicy = PhotoBlobPolicy.ON_DEMAND, filesPolicy = FileBlobPolicy.ON_DEMAND),
            FakeConstraints(), api, cache, gallery = gallery(photo("a")),
            workspace = workspace(FileEntry(id = "f1", blob = "b1")),
        )
        pf.maybePrefetchOnUnlock()
        awaitIdle()
        assertTrue(api.galleryRefs.isEmpty())
        assertTrue(api.fileRefs.isEmpty())
    }

    @Test
    fun disabled_master_switch_runs_nothing() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(enabled = false, photosPolicy = PhotoBlobPolicy.ALL, filesPolicy = FileBlobPolicy.ALL),
            FakeConstraints(), api, cache, gallery = gallery(photo("a")),
        )
        pf.prefetchNow()
        awaitIdle()
        assertTrue(api.galleryRefs.isEmpty())
    }
}
