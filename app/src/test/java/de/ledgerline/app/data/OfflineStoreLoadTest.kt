package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.data.remote.dto.StoreResponse
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * A2 — network-first, cache-fallback for the sealed store manifests.
 * An online [WorkspaceRepository.load] writes the envelope through to the
 * [StoreDiskCache]; a subsequent offline load serves it from disk.
 */
class OfflineStoreLoadTest {

    private val vk = ByteArray(32)
    private val crypto = SealTagCrypto()

    // ---- Workspace ------------------------------------------------------------

    // Store v3: the repo fans out to per-module stores. This fake serves the `todos`
    // module its [body] (notes graduated to the sharded /notes/store, so this exercises a
    // still-monolith module); other modules are empty (null ciphertext, v0). When [fail]
    // is set every module GET throws (offline); notesStore also throws so the notes slice
    // has nothing to serve online either.
    private class WorkspaceApi(val body: StoreResponse?, val fail: Boolean) : NotImplementedApi() {
        override suspend fun moduleStore(module: String): Response<StoreResponse> = when {
            fail -> throw java.io.IOException("offline")
            module == "todos" -> Response.success(body!!)
            else -> Response.success(StoreResponse(null, 0))
        }
        override suspend fun notesStore(): Response<StoreResponse> =
            if (fail) throw java.io.IOException("offline") else Response.success(StoreResponse(null, 0))
    }

    @Test fun workspace_online_writes_cache_then_offline_reads_it() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(vk) }
        val storeCache = tmpStoreCache()

        val todosJson = """{"v":3,"todos":[{"id":"n1","title":"Docs"}]}"""
        val onlineApi = WorkspaceApi(StoreResponse("SEALED:$todosJson", 7), fail = false)
        val online = WorkspaceRepository(
            sh, vh, crypto, WorkspaceCache(), storeCache, FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), tmpOutbox(), FakeConnectivity(), apiProvider = { onlineApi },
        )

        val first = online.load()
        assertTrue(first is Outcome.Ok)
        // The todos module envelope is persisted with its server version + ciphertext.
        val env = storeCache.get("workspace_todos")
        assertNotNull(env)
        assertEquals(7, env!!.version)
        assertEquals("SEALED:$todosJson", env.ciphertext)

        // New repo instance sharing the SAME cache, but the network now fails: every
        // module (incl. the empty ones cached above) is served from disk.
        val offlineApi = WorkspaceApi(null, fail = true)
        val offline = WorkspaceRepository(
            sh, vh, crypto, WorkspaceCache(), storeCache, FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), tmpOutbox(), FakeConnectivity(), apiProvider = { offlineApi },
        )
        val second = offline.load()
        assertTrue(second is Outcome.Ok)
        val ws = (second as Outcome.Ok).value
        assertEquals(listOf("Docs"), ws.manifest.todos.map { it.title })
    }

    @Test fun workspace_offline_without_cache_returns_network_error() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(vk) }
        val api = WorkspaceApi(null, fail = true)
        val repo = WorkspaceRepository(
            sh, vh, crypto, WorkspaceCache(), tmpStoreCache(), FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), tmpOutbox(), FakeConnectivity(), apiProvider = { api },
        )
        assertTrue(repo.load() is Outcome.Err)
    }

    @Test fun workspace_offline_disabled_flag_skips_cache() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(vk) }
        val storeCache = tmpStoreCache()
        // Populate the cache directly, but the master flag is OFF → must not be consulted.
        storeCache.put("workspace_notes", de.ledgerline.app.core.offline.StoreEnvelope("SEALED:{}", 3))
        val api = WorkspaceApi(null, fail = true)
        val repo = WorkspaceRepository(
            sh, vh, crypto, WorkspaceCache(), storeCache, FakeOfflineFlags(enabled = false), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), tmpOutbox(), FakeConnectivity(), apiProvider = { api },
        )
        assertTrue(repo.load() is Outcome.Err)
    }

    // A workspace whose files slice is a v3 sharded root (one file shard + a folders
    // collection). `rawFile` serves the framed-echo ciphertext online; offline every GET
    // throws so the files slice must assemble from the shared blob cache. Modules are empty.
    private class ShardedFilesApi(
        val filesRoot: StoreResponse?,
        val blobs: Map<String, ByteArray>,
        val fail: Boolean,
    ) : NotImplementedApi() {
        override suspend fun moduleStore(module: String): Response<StoreResponse> =
            if (fail) throw java.io.IOException("offline") else Response.success(StoreResponse(null, 0))
        override suspend fun filesStore(): Response<StoreResponse> =
            if (fail) throw java.io.IOException("offline") else Response.success(filesRoot!!)
        override suspend fun rawFile(blob: String): Response<ResponseBody> {
            if (fail) throw java.io.IOException("offline")
            val b = blobs[blob] ?: return Response.error(404, ResponseBody.create(null, ""))
            return Response.success(ResponseBody.create(null, b))
        }
    }

    @Test fun files_v3_online_caches_shard_blobs_then_offline_assembles_them() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(vk) }
        val storeCache = tmpStoreCache()
        val blobCache = tmpBlobCache()   // SHARED across online + offline repos

        val fileArray = """[{"id":"f1","name":"root.txt","folder":null}]"""
        val folderArray = """[{"id":"d1","name":"Docs","parent":null}]"""
        val blobs = mapOf(
            "file-shard" to frameForEchoDecrypt(fileArray.toByteArray(), crypto),
            "folder-coll" to frameForEchoDecrypt(folderArray.toByteArray(), crypto),
        )
        val rootJson = """{"v":3,"shardBits":0,"shards":[{"ref":"file-shard","key":"k","bucket":0}],""" +
            """"foldersRef":"folder-coll","foldersKey":"k"}"""

        val online = WorkspaceRepository(
            sh, vh, crypto, WorkspaceCache(), storeCache, FakeOfflineFlags(),
            de.ledgerline.app.core.offline.DegradedState(), blobCache, tmpOutbox(), FakeConnectivity(),
            apiProvider = { ShardedFilesApi(StoreResponse("SEALED:$rootJson", 5), blobs, fail = false) },
        )
        val first = online.load()
        assertTrue(first is Outcome.Ok)
        assertEquals(listOf("root.txt"), (first as Outcome.Ok).value.manifest.files.map { it.name })
        assertTrue(blobCache.has("file-shard"))
        assertTrue(blobCache.has("folder-coll"))
        // The files-store root envelope is persisted for cold offline assembly.
        assertNotNull(storeCache.get("workspace_files_root"))

        // Offline: every workspace GET throws — the files slice must assemble from the cache.
        val offline = WorkspaceRepository(
            sh, vh, crypto, WorkspaceCache(), storeCache, FakeOfflineFlags(),
            de.ledgerline.app.core.offline.DegradedState(), blobCache, tmpOutbox(), FakeConnectivity(),
            apiProvider = { ShardedFilesApi(null, blobs, fail = true) },
        )
        val res = offline.load()
        assertTrue(res is Outcome.Ok)
        val m = (res as Outcome.Ok).value.manifest
        assertEquals(listOf("root.txt"), m.files.map { it.name })
        assertEquals(listOf("Docs"), m.fileFolders.map { it.name })
    }
}
