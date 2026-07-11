package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.dto.PairClaimRequest
import de.ledgerline.app.data.remote.dto.PairClaimResponse
import de.ledgerline.app.data.remote.dto.PairPollResponse
import de.ledgerline.app.data.remote.dto.ProcessResponse
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.data.remote.dto.StoreResponse
import de.ledgerline.app.data.remote.dto.UploadResponse
import de.ledgerline.app.data.remote.dto.UsageResponse
import de.ledgerline.app.data.remote.dto.VaultResponse
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class WorkspaceSaveTest {

    // Fake crypto: sealManifest returns the raw json (tagged), openManifest strips the tag.
    private val fakeCrypto = object : Crypto {
        override fun sealManifest(json: String, vk: ByteArray) = "SEALED:$json"
        override fun openManifest(ciphertext: String, vk: ByteArray) = ciphertext.removePrefix("SEALED:")
        override fun deriveKek(passphrase: ByteArray, salt: ByteArray, opsLimit: Long, memLimit: Long) = ByteArray(32)
        override fun secretBoxOpen(cipher: ByteArray, nonce: ByteArray, key: ByteArray) = ByteArray(0)
        override fun genericHash32(input: ByteArray) = ByteArray(32)
        override fun b64decode(s: String) = s.toByteArray()
        override fun b64encode(b: ByteArray) = String(b)
        override fun fromHex(s: String) = s.toByteArray()
        override val contentChunkSize = 1
        override fun u32le(n: Int) = ByteArray(4)
        override fun readU32le(b: ByteArray, o: Int) = 0
        override fun newContentEncryptor(vk: ByteArray): Crypto.ContentEncryptor = throw NotImplementedError()
        override fun contentDecryptor(encFileKey: String, vk: ByteArray): Crypto.ContentDecryptor = throw NotImplementedError()
    }

    // Fake API: first PUT → 409, GET returns version 5 with one folder, second PUT → 200 version 6.
    private class FakeApi(val manifestJson: String) : LedgerlineApi {
        var puts = 0

        override suspend fun store(): Response<StoreResponse> =
            Response.success(StoreResponse("SEALED:$manifestJson", 5))

        override suspend fun putStore(body: StorePutRequest): Response<StoreResponse> {
            puts++
            return if (puts == 1)
                Response.error(409, ResponseBody.create(null, ""))
            else
                Response.success(StoreResponse(body.ciphertext, body.version + 1))
        }

        // Unused endpoints — throw so any accidental call is visible.
        override suspend fun claimPair(b: PairClaimRequest): Response<PairClaimResponse> = throw NotImplementedError()
        override suspend fun pollPair(c: String): Response<PairPollResponse> = throw NotImplementedError()
        override suspend fun me(): Response<de.ledgerline.app.data.remote.dto.MeResponse> = throw NotImplementedError()
        override suspend fun vault(): Response<VaultResponse> = throw NotImplementedError()
        override suspend fun rawFile(blob: String): Response<ResponseBody> = throw NotImplementedError()
        override suspend fun uploadFile(file: MultipartBody.Part): Response<UploadResponse> = throw NotImplementedError()
        override suspend fun deleteBlob(blob: String): Response<Unit> = throw NotImplementedError()
        override suspend fun deleteGalleryBlob(blob: String): Response<Unit> = throw NotImplementedError()
        override suspend fun embedText(body: de.ledgerline.app.data.remote.dto.EmbedTextRequest): Response<de.ledgerline.app.data.remote.dto.EmbedTextResponse> = throw NotImplementedError()
        override suspend fun filesUsage(): Response<UsageResponse> = throw NotImplementedError()
        override suspend fun deleteSession(): Response<Unit> = throw NotImplementedError()
        override suspend fun galleryStore(): Response<StoreResponse> = throw NotImplementedError()
        override suspend fun galleryRaw(blob: String): Response<okhttp3.ResponseBody> = throw NotImplementedError()
        override suspend fun galleryUsage(): Response<UsageResponse> = throw NotImplementedError()
        override suspend fun galleryUpload(file: MultipartBody.Part): Response<UploadResponse> = throw NotImplementedError()
        override suspend fun galleryProcess(file: MultipartBody.Part): Response<ProcessResponse> = throw NotImplementedError()
        override suspend fun galleryStorePut(body: StorePutRequest): Response<StoreResponse> = throw NotImplementedError()
    }

    @Test fun save_merges_on_409_and_retries() = runBlocking {
        val session = Session("https://h", "tok", "sha256/x", null)
        val sh = SessionHolder().apply { set(session) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val cache = WorkspaceCache()
        val fakeApi = FakeApi("""{"v":1,"fileFolders":[{"id":"d1","name":"Docs"}]}""")

        val repo = WorkspaceRepository(sh, vh, fakeCrypto, cache, tmpStoreCache(), FakeOfflineFlags(), apiProvider = { fakeApi })

        val result = repo.save { m ->
            m.copy(fileFolders = m.fileFolders + NamedFolder("d2", "New", null))
        }

        assertTrue(result is Outcome.Ok)
        // After 409 the repo reloads (server has Docs) and re-applies mutate → both folders present.
        val names = (result as Outcome.Ok).value.manifest.fileFolders.map { it.name }.toSet()
        assertEquals(setOf("Docs", "New"), names)
        assertEquals(2, fakeApi.puts)   // 409 then success
        assertEquals(6, result.value.version)
    }
}
