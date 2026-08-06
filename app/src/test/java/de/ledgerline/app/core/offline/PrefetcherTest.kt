package de.ledgerline.app.core.offline

import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.ops.BackgroundOpsSetting
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.core.ops.ServiceController
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.FakeOfflineFlags
import de.ledgerline.app.data.FileBlobRepository
import de.ledgerline.app.data.NotImplementedApi
import de.ledgerline.app.data.SealTagCrypto
import de.ledgerline.app.data.contactBlobRepoForTest
import de.ledgerline.app.data.offline.FileBlobPolicy
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.domain.model.FileEntry
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
        val fileRefs = mutableListOf<String>()
        private fun u32le(n: Int) = byteArrayOf(
            (n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte(),
            ((n shr 16) and 0xFF).toByte(), ((n shr 24) and 0xFF).toByte(),
        )
        override suspend fun rawFile(blob: String): Response<ResponseBody> {
            fileRefs.add(blob)
            return Response.success(byteArrayOf(4, 5, 6).toResponseBody("application/octet-stream".toMediaType()))
        }
        // Files batch through raw-batch.
        override suspend fun filesRawBatch(body: de.ledgerline.app.data.remote.dto.ReconcileRequest): Response<ResponseBody> {
            fileRefs.addAll(body.blobs)
            val out = java.io.ByteArrayOutputStream()
            for (id in body.blobs) {
                val idb = id.toByteArray(Charsets.UTF_8)
                out.write(u32le(idb.size)); out.write(idb); out.write(u32le(3)); out.write(byteArrayOf(4, 5, 6))
            }
            return Response.success(out.toByteArray().toResponseBody("application/octet-stream".toMediaType()))
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

    private fun fileRepo(api: LedgerlineApi, cache: BlobDiskCache): FileBlobRepository {
        val sh = SessionHolder().apply { set(Session("https://x", "tok", "", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        return FileBlobRepository(sh, vh, SealTagCrypto(), cache, FakeOfflineFlags(), apiProvider = { api })
    }

    private fun prefetcher(
        flags: OfflineFlags,
        constraints: Constraints,
        api: RecordingApi,
        cache: BlobDiskCache,
        workspace: Workspace? = null,
    ): Prefetcher {
        val wc = WorkspaceCache().apply { workspace?.let { set(it) } }
        val sh = SessionHolder().apply { set(Session("https://x", "tok", "", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val contactRepo = contactBlobRepoForTest(sh, vh, SealTagCrypto(), cache, FakeOfflineFlags(), api)
        return Prefetcher(wc, fileRepo(api, cache), contactRepo, cache, flags, constraints, opManager())
    }

    private fun awaitIdle() = Thread.sleep(200)

    private fun workspace(vararg files: FileEntry) = Workspace(WorkspaceManifest(files = files.toList()), version = 1)

    @Test
    fun files_all_enumerates_blobs_else_none() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(filesPolicy = FileBlobPolicy.ALL),
            FakeConstraints(), api, cache,
            workspace = workspace(
                FileEntry(id = "f1", blob = "b1"),
                FileEntry(id = "f2", blob = "b2", trashed = true),
            ),
        )
        pf.prefetchNow()
        awaitIdle()
        assertEquals(setOf("b1"), api.fileRefs.toSet())
    }

    @Test
    fun files_on_demand_enumerates_nothing() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(filesPolicy = FileBlobPolicy.ON_DEMAND),
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
        cache.put("b1", byteArrayOf(9))
        val pf = prefetcher(
            FakeOfflineFlags(filesPolicy = FileBlobPolicy.ALL),
            FakeConstraints(), api, cache,
            workspace = workspace(FileEntry(id = "f1", blob = "b1"), FileEntry(id = "f2", blob = "b2")),
        )
        pf.prefetchNow()
        awaitIdle()
        // b1 already cached → not refetched; only b2 hits the network.
        assertEquals(setOf("b2"), api.fileRefs.toSet())
    }

    @Test
    fun constraint_failure_prefetches_nothing_and_sets_message_on_manual() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(filesPolicy = FileBlobPolicy.ALL, wifiOnly = true),
            FakeConstraints(wifi = false), api, cache,
            workspace = workspace(FileEntry(id = "f1", blob = "b1")),
        )
        pf.prefetchNow()
        awaitIdle()
        assertTrue(api.fileRefs.isEmpty())
        assertEquals("constraints", pf.message.value)
        pf.clearMessage()
        assertNull(pf.message.value)
    }

    @Test
    fun auto_with_no_prefetch_policy_runs_nothing() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(filesPolicy = FileBlobPolicy.ON_DEMAND),
            FakeConstraints(), api, cache,
            workspace = workspace(FileEntry(id = "f1", blob = "b1")),
        )
        pf.maybePrefetchOnUnlock()
        awaitIdle()
        assertTrue(api.fileRefs.isEmpty())
    }

    @Test
    fun disabled_master_switch_runs_nothing() {
        val api = RecordingApi()
        val cache = tmpCache()
        val pf = prefetcher(
            FakeOfflineFlags(enabled = false, filesPolicy = FileBlobPolicy.ALL),
            FakeConstraints(), api, cache,
            workspace = workspace(FileEntry(id = "f1", blob = "b1")),
        )
        pf.prefetchNow()
        awaitIdle()
        assertTrue(api.fileRefs.isEmpty())
    }
}
