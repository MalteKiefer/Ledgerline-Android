package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * A2 — network-first, cache-fallback for content blobs. An online download caches
 * the ciphertext bytes; a subsequent offline download decrypts them from disk.
 * Uses [SealTagCrypto]'s echo decryptor so a framed "ciphertext" round-trips to its
 * plaintext without native libsodium.
 */
class OfflineBlobDownloadTest {

    private val vk = ByteArray(32)
    private val crypto = SealTagCrypto()
    private val plaintext = "hello-offline".toByteArray()
    private val cipher get() = frameForEchoDecrypt(plaintext, crypto)

    // ---- Files ----------------------------------------------------------------

    private class FilesApi(val bytes: ByteArray?, val fail: Boolean) : NotImplementedApi() {
        override suspend fun rawFile(blob: String): Response<ResponseBody> =
            if (fail) throw java.io.IOException("offline")
            else Response.success(bytes!!.toResponseBody("application/octet-stream".toMediaTypeOrNull()))
    }

    private fun filesRepo(api: NotImplementedApi, blobCache: de.ledgerline.app.core.offline.BlobDiskCache, flags: FakeOfflineFlags) =
        FileBlobRepository(
            SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) },
            VaultKeyHolder().apply { set(vk) },
            crypto, blobCache, flags, apiProvider = { api },
        )

    @Test fun files_online_caches_then_offline_serves_from_cache() = runBlocking {
        val blobCache = tmpBlobCache()
        val online = filesRepo(FilesApi(cipher, fail = false), blobCache, FakeOfflineFlags())
        val first = online.downloadToBytes("blob-1", "encKey")
        assertTrue(first is Outcome.Ok)
        assertArrayEquals(plaintext, (first as Outcome.Ok).value)
        // Ciphertext (not plaintext) landed in the cache.
        assertArrayEquals(cipher, blobCache.get("blob-1"))

        val offline = filesRepo(FilesApi(null, fail = true), blobCache, FakeOfflineFlags())
        val second = offline.downloadToBytes("blob-1", "encKey")
        assertTrue(second is Outcome.Ok)
        assertArrayEquals(plaintext, (second as Outcome.Ok).value)
    }

    @Test fun files_offline_without_cache_returns_error() = runBlocking {
        val offline = filesRepo(FilesApi(null, fail = true), tmpBlobCache(), FakeOfflineFlags())
        assertTrue(offline.downloadToBytes("missing", "encKey") is Outcome.Err)
    }

    @Test fun files_module_flag_off_does_not_cache() = runBlocking {
        val blobCache = tmpBlobCache()
        val repo = filesRepo(FilesApi(cipher, fail = false), blobCache, FakeOfflineFlags(filesPolicy = de.ledgerline.app.data.offline.FileBlobPolicy.OFF))
        assertTrue(repo.downloadToBytes("blob-x", "encKey") is Outcome.Ok)
        assertTrue(blobCache.get("blob-x") == null) // flag off → nothing cached
    }
}
