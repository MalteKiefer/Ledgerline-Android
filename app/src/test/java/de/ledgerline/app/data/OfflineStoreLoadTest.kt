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
            sh, vh, crypto, WorkspaceCache(), storeCache, FakeOfflineFlags(), apiProvider = { onlineApi },
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
            sh, vh, crypto, WorkspaceCache(), storeCache, FakeOfflineFlags(), apiProvider = { offlineApi },
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
            sh, vh, crypto, WorkspaceCache(), tmpStoreCache(), FakeOfflineFlags(), apiProvider = { api },
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
            sh, vh, crypto, WorkspaceCache(), storeCache, FakeOfflineFlags(enabled = false), apiProvider = { api },
        )
        assertTrue(repo.load() is Outcome.Err)
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
            galleryUpload = NoGalleryUpload,
            apiProvider = { GalleryApi(StoreResponse("SEALED:$manifestJson", 4), code = null) },
        )
        assertTrue(online.load() is Outcome.Ok)
        assertEquals(4, storeCache.get("gallery")!!.version)

        // Non-2xx (503, not 401) → cache fallback.
        val offline = GalleryRepository(
            sh, vh, crypto, GalleryCache(), storeCache, FakeOfflineFlags(),
            galleryUpload = NoGalleryUpload,
            apiProvider = { GalleryApi(null, code = 503) },
        )
        val res = offline.load()
        assertTrue(res is Outcome.Ok)
        assertEquals(listOf("p1"), (res as Outcome.Ok).value.manifest.photos.map { it.id })
        assertEquals(4, res.value.version)
    }

    @Test fun gallery_401_never_falls_back_to_cache() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(vk) }
        val storeCache = tmpStoreCache()
        storeCache.put("gallery", de.ledgerline.app.core.offline.StoreEnvelope("SEALED:{}", 9))
        val repo = GalleryRepository(
            sh, vh, crypto, GalleryCache(), storeCache, FakeOfflineFlags(),
            galleryUpload = NoGalleryUpload,
            apiProvider = { GalleryApi(null, code = 401) },
        )
        // 401 → HTTP error (forced-logout path), NOT the cached manifest.
        val res = repo.load()
        assertTrue(res is Outcome.Err)
        assertEquals(de.ledgerline.app.core.ErrorKind.HTTP, (res as Outcome.Err).kind)
    }
}
