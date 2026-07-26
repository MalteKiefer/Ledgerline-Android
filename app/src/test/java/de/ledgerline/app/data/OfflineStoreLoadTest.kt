package de.ledgerline.app.data

import de.ledgerline.app.core.GalleryCache
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
 * An online [WorkspaceRepository.load]/[GalleryRepository.load] writes the envelope
 * through to the [StoreDiskCache]; a subsequent offline load serves it from disk.
 */
class OfflineStoreLoadTest {

    private val vk = ByteArray(32)
    private val crypto = SealTagCrypto()

    // ---- Workspace ------------------------------------------------------------

    // Store v3: the repo fans out to per-module stores. This fake serves the `notes`
    // module its [body]; other modules are empty (null ciphertext, v0). When [fail]
    // is set every module GET throws (offline).
    private class WorkspaceApi(val body: StoreResponse?, val fail: Boolean) : NotImplementedApi() {
        override suspend fun moduleStore(module: String): Response<StoreResponse> = when {
            fail -> throw java.io.IOException("offline")
            module == "notes" -> Response.success(body!!)
            else -> Response.success(StoreResponse(null, 0))
        }
    }

    @Test fun workspace_online_writes_cache_then_offline_reads_it() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(vk) }
        val storeCache = tmpStoreCache()

        val notesJson = """{"v":3,"notes":[{"id":"n1","title":"Docs"}]}"""
        val onlineApi = WorkspaceApi(StoreResponse("SEALED:$notesJson", 7), fail = false)
        val online = WorkspaceRepository(
            sh, vh, crypto, WorkspaceCache(), storeCache, FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), apiProvider = { onlineApi },
        )

        val first = online.load()
        assertTrue(first is Outcome.Ok)
        // The notes module envelope is persisted with its server version + ciphertext.
        val env = storeCache.get("workspace_notes")
        assertNotNull(env)
        assertEquals(7, env!!.version)
        assertEquals("SEALED:$notesJson", env.ciphertext)

        // New repo instance sharing the SAME cache, but the network now fails: every
        // module (incl. the empty ones cached above) is served from disk.
        val offlineApi = WorkspaceApi(null, fail = true)
        val offline = WorkspaceRepository(
            sh, vh, crypto, WorkspaceCache(), storeCache, FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), apiProvider = { offlineApi },
        )
        val second = offline.load()
        assertTrue(second is Outcome.Ok)
        val ws = (second as Outcome.Ok).value
        assertEquals(listOf("Docs"), ws.manifest.notes.map { it.title })
    }

    @Test fun workspace_offline_without_cache_returns_network_error() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(vk) }
        val api = WorkspaceApi(null, fail = true)
        val repo = WorkspaceRepository(
            sh, vh, crypto, WorkspaceCache(), tmpStoreCache(), FakeOfflineFlags(), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), apiProvider = { api },
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
            sh, vh, crypto, WorkspaceCache(), storeCache, FakeOfflineFlags(enabled = false), de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), apiProvider = { api },
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
            de.ledgerline.app.core.offline.DegradedState(), blobCache,
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
            de.ledgerline.app.core.offline.DegradedState(), blobCache,
            apiProvider = { ShardedFilesApi(null, blobs, fail = true) },
        )
        val res = offline.load()
        assertTrue(res is Outcome.Ok)
        val m = (res as Outcome.Ok).value.manifest
        assertEquals(listOf("root.txt"), m.files.map { it.name })
        assertEquals(listOf("Docs"), m.fileFolders.map { it.name })
    }

    // ---- Gallery --------------------------------------------------------------

    private class GalleryApi(val body: StoreResponse?, val code: Int?) : NotImplementedApi() {
        override suspend fun galleryStore(): Response<StoreResponse> = when {
            code != null -> Response.error(code, ResponseBody.create(null, ""))
            else -> Response.success(body!!)
        }
    }

    @Test fun gallery_online_writes_cache_then_offline_503_reads_it() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(vk) }
        val storeCache = tmpStoreCache()

        val manifestJson = """{"v":1,"photos":[{"id":"p1"}],"albums":[],"people":[]}"""
        val online = GalleryRepository(
            sh, vh, crypto, GalleryCache(), storeCache, FakeOfflineFlags(),
            galleryUpload = NoGalleryUpload, degradedState = de.ledgerline.app.core.offline.DegradedState(),
            blobCache = tmpBlobCache(),
            apiProvider = { GalleryApi(StoreResponse("SEALED:$manifestJson", 4), code = null) },
        )
        assertTrue(online.load() is Outcome.Ok)
        assertEquals(4, storeCache.get("gallery")!!.version)

        // Non-2xx (503, not 401) → cache fallback.
        val offline = GalleryRepository(
            sh, vh, crypto, GalleryCache(), storeCache, FakeOfflineFlags(),
            galleryUpload = NoGalleryUpload, degradedState = de.ledgerline.app.core.offline.DegradedState(),
            blobCache = tmpBlobCache(),
            apiProvider = { GalleryApi(null, code = 503) },
        )
        val res = offline.load()
        assertTrue(res is Outcome.Ok)
        assertEquals(listOf("p1"), (res as Outcome.Ok).value.manifest.photos.map { it.id })
        assertEquals(4, res.value.version)
    }

    // A v3 sharded root: one photo shard blob + a people collection blob. `galleryRaw`
    // serves the framed-echo ciphertext online; offline (fail=true) it throws so assembly
    // must come from the shared blob cache the online load populated.
    private class ShardedGalleryApi(
        val store: StoreResponse?,
        val storeCode: Int?,
        val blobs: Map<String, ByteArray>,
        val fail: Boolean,
    ) : NotImplementedApi() {
        override suspend fun galleryStore(): Response<StoreResponse> = when {
            storeCode != null -> Response.error(storeCode, ResponseBody.create(null, ""))
            else -> Response.success(store!!)
        }
        override suspend fun galleryRaw(blob: String): Response<ResponseBody> {
            if (fail) throw java.io.IOException("offline")
            val b = blobs[blob] ?: return Response.error(404, ResponseBody.create(null, ""))
            return Response.success(ResponseBody.create(null, b))
        }
    }

    @Test fun gallery_v3_online_caches_shard_blobs_then_offline_assembles_them() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(vk) }
        val storeCache = tmpStoreCache()
        val blobCache = tmpBlobCache()   // SHARED across online + offline repos

        // Blob payloads: a photo-shard record array and a people-collection record array,
        // framed so SealTagCrypto's echo decryptor returns them verbatim.
        val shardArray = """[{"id":"p1"},{"id":"p2"}]"""
        val peopleArray = """[{"id":"per1","name":"Alice"}]"""
        val blobs = mapOf(
            "shard-a" to frameForEchoDecrypt(shardArray.toByteArray(), crypto),
            "coll-p" to frameForEchoDecrypt(peopleArray.toByteArray(), crypto),
        )
        val rootJson = """{"v":3,"shardBits":0,"shards":[{"ref":"shard-a","key":"k","bucket":0}],""" +
            """"peopleRef":"coll-p","peopleKey":"k"}"""

        val online = GalleryRepository(
            sh, vh, crypto, GalleryCache(), storeCache, FakeOfflineFlags(),
            galleryUpload = NoGalleryUpload, degradedState = de.ledgerline.app.core.offline.DegradedState(),
            blobCache = blobCache,
            apiProvider = { ShardedGalleryApi(StoreResponse("SEALED:$rootJson", 8), storeCode = null, blobs = blobs, fail = false) },
        )
        val first = online.load()
        assertTrue(first is Outcome.Ok)
        assertEquals(setOf("p1", "p2"), (first as Outcome.Ok).value.manifest.photos.map { it.id }.toSet())
        // The shard + collection ciphertext were written through to the shared blob cache.
        assertTrue(blobCache.has("shard-a"))
        assertTrue(blobCache.has("coll-p"))

        // Offline: the store GET 503s AND galleryRaw throws — assembly must be served entirely
        // from the cached root + cached shard/collection blobs.
        val offline = GalleryRepository(
            sh, vh, crypto, GalleryCache(), storeCache, FakeOfflineFlags(),
            galleryUpload = NoGalleryUpload, degradedState = de.ledgerline.app.core.offline.DegradedState(),
            blobCache = blobCache,
            apiProvider = { ShardedGalleryApi(null, storeCode = 503, blobs = blobs, fail = true) },
        )
        val res = offline.load()
        assertTrue(res is Outcome.Ok)
        val m = (res as Outcome.Ok).value.manifest
        assertEquals(setOf("p1", "p2"), m.photos.map { it.id }.toSet())
        assertEquals(listOf("Alice"), m.people.map { it.name })
        assertEquals(8, res.value.version)
    }

    @Test fun gallery_401_never_falls_back_to_cache() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(vk) }
        val storeCache = tmpStoreCache()
        storeCache.put("gallery", de.ledgerline.app.core.offline.StoreEnvelope("SEALED:{}", 9))
        val repo = GalleryRepository(
            sh, vh, crypto, GalleryCache(), storeCache, FakeOfflineFlags(),
            galleryUpload = NoGalleryUpload, degradedState = de.ledgerline.app.core.offline.DegradedState(),
            blobCache = tmpBlobCache(),
            apiProvider = { GalleryApi(null, code = 401) },
        )
        // 401 → HTTP error (forced-logout path), NOT the cached manifest.
        val res = repo.load()
        assertTrue(res is Outcome.Err)
        assertEquals(de.ledgerline.app.core.ErrorKind.HTTP, (res as Outcome.Err).kind)
    }
}
