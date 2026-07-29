package de.ledgerline.app.data

import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
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
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class GallerySaveTest {

    // Fake crypto: sealManifest tags the json, openManifest strips the tag.
    private val fakeCrypto = object : Crypto {
        override fun sealManifest(json: String, vk: ByteArray) = "SEALED:$json"
        override fun openManifest(ciphertext: String, vk: ByteArray) = ciphertext.removePrefix("SEALED:")
        override fun sealValue(data: ByteArray, key: ByteArray) = "V:" + String(data, Charsets.ISO_8859_1)
        override fun openValue(cn: String, key: ByteArray) = cn.removePrefix("V:").toByteArray(Charsets.ISO_8859_1)
        override fun genericHash(input: ByteArray, outLen: Int) = ByteArray(outLen)
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

    // Fake API: galleryStore returns a manifest with one existing photo at version 5.
    // galleryStorePut → 409 on first call, then success (version 6) on second call.
    // All other methods throw NotImplementedError.
    private class FakeApi(val manifestJson: String) : LedgerlineApi {
        var puts = 0
        var lastReconcile: List<String>? = null

        override suspend fun galleryReverse(lat: Double, lng: Double): Response<de.ledgerline.app.data.remote.dto.ReverseResponse> = throw NotImplementedError()
        override suspend fun mapsRoute(points: String): Response<de.ledgerline.app.data.remote.dto.MapsRouteResponse> = throw NotImplementedError()
        override suspend fun notesStore(): Response<de.ledgerline.app.data.remote.dto.StoreResponse> = throw NotImplementedError()
        override suspend fun notesStorePut(body: de.ledgerline.app.data.remote.dto.StorePutRequest): Response<de.ledgerline.app.data.remote.dto.StoreResponse> = throw NotImplementedError()
        override suspend fun rawNote(blob: String): Response<okhttp3.ResponseBody> = throw NotImplementedError()
        override suspend fun uploadNote(file: okhttp3.MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.UploadResponse> = throw NotImplementedError()
        override suspend fun notesReconcile(body: de.ledgerline.app.data.remote.dto.ReconcileRequest): Response<de.ledgerline.app.data.remote.dto.ReconcileResponse> = throw NotImplementedError()
        override suspend fun filesRawBatch(body: de.ledgerline.app.data.remote.dto.ReconcileRequest): Response<okhttp3.ResponseBody> = throw NotImplementedError()
        override suspend fun notesRawBatch(body: de.ledgerline.app.data.remote.dto.ReconcileRequest): Response<okhttp3.ResponseBody> = throw NotImplementedError()
        override suspend fun passwordsRawBatch(body: de.ledgerline.app.data.remote.dto.ReconcileRequest): Response<okhttp3.ResponseBody> = throw NotImplementedError()
        override suspend fun passwordsReconcile(body: de.ledgerline.app.data.remote.dto.ReconcileRequest): Response<de.ledgerline.app.data.remote.dto.ReconcileResponse> = throw NotImplementedError()
        override suspend fun passwordsStore(): Response<de.ledgerline.app.data.remote.dto.StoreResponse> = throw NotImplementedError()
        override suspend fun invoicesStore(): Response<de.ledgerline.app.data.remote.dto.StoreResponse> = throw NotImplementedError()
        override suspend fun invoicesStorePut(body: de.ledgerline.app.data.remote.dto.StorePutRequest): Response<de.ledgerline.app.data.remote.dto.StoreResponse> = throw NotImplementedError()
        override suspend fun uploadInvoice(file: okhttp3.MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.UploadResponse> = throw NotImplementedError()
        override suspend fun rawInvoice(blob: String): Response<okhttp3.ResponseBody> = throw NotImplementedError()
        override suspend fun invoicesRawBatch(body: de.ledgerline.app.data.remote.dto.ReconcileRequest): Response<okhttp3.ResponseBody> = throw NotImplementedError()
        override suspend fun invoicesReconcile(body: de.ledgerline.app.data.remote.dto.ReconcileRequest): Response<de.ledgerline.app.data.remote.dto.ReconcileResponse> = throw NotImplementedError()
        override suspend fun companyLogo(): Response<okhttp3.ResponseBody> = throw NotImplementedError()
        override suspend fun avatar(): Response<okhttp3.ResponseBody> = throw NotImplementedError()
        override suspend fun deviceHeartbeat(body: de.ledgerline.app.data.remote.dto.HeartbeatRequest): Response<de.ledgerline.app.data.remote.dto.HeartbeatResponse> = throw NotImplementedError()
        override suspend fun invoicesOcr(file: okhttp3.MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.OcrResponse> = throw NotImplementedError()
        override suspend fun company(): Response<de.ledgerline.app.data.remote.dto.CompanyResponse> = throw NotImplementedError()
    override suspend fun companyPut(body: de.ledgerline.app.data.remote.dto.CompanyDto): Response<de.ledgerline.app.data.remote.dto.CompanyResponse> = throw NotImplementedError()
        override suspend fun passwordsStorePut(body: de.ledgerline.app.data.remote.dto.StorePutRequest): Response<de.ledgerline.app.data.remote.dto.StoreResponse> = throw NotImplementedError()
        override suspend fun rawPassword(blob: String): Response<okhttp3.ResponseBody> = throw NotImplementedError()
        override suspend fun uploadPassword(file: okhttp3.MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.UploadResponse> = throw NotImplementedError()

        override suspend fun galleryStore(): Response<StoreResponse> =
            Response.success(StoreResponse("SEALED:$manifestJson", 5))

        override suspend fun galleryStorePut(body: StorePutRequest): Response<StoreResponse> {
            puts++
            return if (puts == 1)
                Response.error(409, ResponseBody.create(null, ""))
            else
                Response.success(StoreResponse(body.ciphertext, body.version + 1))
        }

        // All other endpoints — throw so any accidental call is visible.
        override suspend fun claimPair(b: PairClaimRequest): Response<PairClaimResponse> = throw NotImplementedError()
        override suspend fun pollPair(body: de.ledgerline.app.data.remote.dto.PairCollectRequest): Response<PairPollResponse> = throw NotImplementedError()
        override suspend fun me(): Response<de.ledgerline.app.data.remote.dto.MeResponse> = throw NotImplementedError()
        override suspend fun putPreferences(body: de.ledgerline.app.data.remote.dto.DisplayPrefsDto): Response<Unit> = throw NotImplementedError()
    override suspend fun devices(): Response<de.ledgerline.app.data.remote.dto.DevicesResponse> = throw NotImplementedError()
        override suspend fun revokeDevice(token: String): Response<Unit> = throw NotImplementedError()
        override suspend fun wipeDevice(token: String): Response<Unit> = throw NotImplementedError()

        override suspend fun passwordsBreach(prefix: String): Response<ResponseBody> = throw NotImplementedError()
        override suspend fun passwordsIcon(domain: String): Response<de.ledgerline.app.data.remote.dto.IconResponse> = throw NotImplementedError()
        override suspend fun passwordsTfaDirectory(): Response<de.ledgerline.app.data.remote.dto.TfaDirectoryResponse> = throw NotImplementedError()
        override suspend fun vault(): Response<VaultResponse> = throw NotImplementedError()
        override suspend fun vaultKeys(): Response<de.ledgerline.app.data.remote.dto.VaultKeysResponse> = throw NotImplementedError()
        override suspend fun putVaultKeys(body: de.ledgerline.app.data.remote.dto.PublishKeysRequest): Response<Unit> = throw NotImplementedError()
        override suspend fun store(): Response<StoreResponse> = throw NotImplementedError()
        override suspend fun moduleStore(module: String): Response<StoreResponse> = throw NotImplementedError()
        override suspend fun putModuleStore(module: String, body: StorePutRequest): Response<StoreResponse> = throw NotImplementedError()
        override suspend fun filesReconcile(body: de.ledgerline.app.data.remote.dto.ReconcileRequest): Response<de.ledgerline.app.data.remote.dto.ReconcileResponse> = throw NotImplementedError()
        override suspend fun galleryReconcile(body: de.ledgerline.app.data.remote.dto.ReconcileRequest): Response<de.ledgerline.app.data.remote.dto.ReconcileResponse> {
            lastReconcile = body.blobs
            return Response.success(de.ledgerline.app.data.remote.dto.ReconcileResponse(0, 0))
        }
        override suspend fun filesStore(): Response<StoreResponse> = throw NotImplementedError()
        override suspend fun filesStorePut(body: StorePutRequest): Response<StoreResponse> = throw NotImplementedError()
        override suspend fun createFileShare(body: de.ledgerline.app.data.remote.dto.ShareCreateRequest): Response<de.ledgerline.app.data.remote.dto.ShareTokenResponse> = throw NotImplementedError()
        override suspend fun updateFileShare(token: String, body: de.ledgerline.app.data.remote.dto.ShareUpdateRequest): Response<de.ledgerline.app.data.remote.dto.ShareTokenResponse> = throw NotImplementedError()
        override suspend fun deleteFileShare(token: String): Response<Unit> = throw NotImplementedError()
        override suspend fun createGalleryShare(body: de.ledgerline.app.data.remote.dto.ShareCreateRequest): Response<de.ledgerline.app.data.remote.dto.ShareTokenResponse> = throw NotImplementedError()
        override suspend fun updateGalleryShare(token: String, body: de.ledgerline.app.data.remote.dto.ShareUpdateRequest): Response<de.ledgerline.app.data.remote.dto.ShareTokenResponse> = throw NotImplementedError()
        override suspend fun deleteGalleryShare(token: String): Response<Unit> = throw NotImplementedError()
        override suspend fun putStore(body: StorePutRequest): Response<StoreResponse> = throw NotImplementedError()
        override suspend fun deleteSession(): Response<Unit> = throw NotImplementedError()
        override suspend fun rawFile(blob: String): Response<ResponseBody> = throw NotImplementedError()
        override suspend fun uploadFile(file: MultipartBody.Part): Response<UploadResponse> = throw NotImplementedError()
        override suspend fun deleteBlob(blob: String): Response<Unit> = throw NotImplementedError()
        override suspend fun deleteGalleryBlob(blob: String): Response<Unit> = throw NotImplementedError()
        override suspend fun embedText(body: de.ledgerline.app.data.remote.dto.EmbedTextRequest): Response<de.ledgerline.app.data.remote.dto.EmbedTextResponse> = throw NotImplementedError()
        override suspend fun filesUsage(): Response<UsageResponse> = throw NotImplementedError()
        override suspend fun galleryRaw(blob: String): Response<ResponseBody> = throw NotImplementedError()
        override suspend fun galleryRawBatch(body: de.ledgerline.app.data.remote.dto.ReconcileRequest): Response<ResponseBody> = throw NotImplementedError()
        override suspend fun galleryUsage(): Response<UsageResponse> = throw NotImplementedError()
        override suspend fun galleryUpload(file: MultipartBody.Part): Response<UploadResponse> = throw NotImplementedError()
        override suspend fun filesUploadInit(body: de.ledgerline.app.data.remote.dto.UploadInitRequest): Response<de.ledgerline.app.data.remote.dto.UploadInitResponse> = throw NotImplementedError()
        override suspend fun filesUploadPart(token: okhttp3.RequestBody, part: okhttp3.RequestBody, chunk: MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.UploadPartResponse> = throw NotImplementedError()
        override suspend fun filesUploadComplete(body: de.ledgerline.app.data.remote.dto.UploadCompleteRequest): Response<UploadResponse> = throw NotImplementedError()
        override suspend fun filesUploadAbort(body: de.ledgerline.app.data.remote.dto.UploadAbortRequest): Response<Unit> = throw NotImplementedError()
        override suspend fun galleryUploadInit(body: de.ledgerline.app.data.remote.dto.UploadInitRequest): Response<de.ledgerline.app.data.remote.dto.UploadInitResponse> = throw NotImplementedError()
        override suspend fun galleryUploadPart(token: okhttp3.RequestBody, part: okhttp3.RequestBody, chunk: MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.UploadPartResponse> = throw NotImplementedError()
        override suspend fun galleryUploadComplete(body: de.ledgerline.app.data.remote.dto.UploadCompleteRequest): Response<UploadResponse> = throw NotImplementedError()
        override suspend fun galleryUploadAbort(body: de.ledgerline.app.data.remote.dto.UploadAbortRequest): Response<Unit> = throw NotImplementedError()
        override suspend fun galleryProcess(file: MultipartBody.Part): Response<ProcessResponse> = throw NotImplementedError()
        override suspend fun contactsUsage(): Response<UsageResponse> = throw NotImplementedError()
        override suspend fun contactsReconcile(body: de.ledgerline.app.data.remote.dto.ReconcileRequest): Response<de.ledgerline.app.data.remote.dto.ReconcileResponse> = throw NotImplementedError()
        override suspend fun contactsUpload(file: MultipartBody.Part): Response<UploadResponse> = throw NotImplementedError()
        override suspend fun contactsRaw(blob: String): Response<ResponseBody> = throw NotImplementedError()
        override suspend fun deleteContactBlob(blob: String): Response<Unit> = throw NotImplementedError()
    }

    @Test fun save_merges_on_409_and_retries() = runBlocking {
        val session = Session("https://h", "tok", "sha256/x", null)
        val sh = SessionHolder().apply { set(session) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val galleryCache = GalleryCache()

        // Server has one existing photo at version 5.
        val existingPhotoJson = """{"v":1,"photos":[{"id":"existing"}],"albums":[],"people":[]}"""
        val fakeApi = FakeApi(existingPhotoJson)

        // Fake uploader: the sharded write uploads photo-shard content blobs; return a
        // deterministic blob id/key so the write can build the v3 root.
        val fakeUpload = object : de.ledgerline.app.domain.usecase.GalleryUploadApi {
            override suspend fun uploadBytes(bytes: ByteArray, name: String) =
                Outcome.Ok(UploadedBlob("shard-blob", "shard-key", bytes.size.toLong()))
            override suspend fun uploadStream(name: String, size: Long, openInput: () -> java.io.InputStream) =
                Outcome.Ok(UploadedBlob("shard-blob", "shard-key", size))
            override suspend fun process(name: String, mime: String, size: Long, openInput: () -> java.io.InputStream) = throw NotImplementedError()
        }

        val repo = GalleryRepository(sh, vh, fakeCrypto, galleryCache, tmpStoreCache(), FakeOfflineFlags(), fakeUpload, de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), apiProvider = { fakeApi })

        val result = repo.save { manifest ->
            manifest.copy(photos = manifest.photos + GalleryPhoto(id = "new"))
        }

        assertTrue(result is Outcome.Ok)
        // After 409 the repo reloads (server has "existing") and re-applies mutate → both photos present.
        val photoIds = (result as Outcome.Ok).value.manifest.photos.map { it.id }.toSet()
        assertEquals(setOf("existing", "new"), photoIds)
        assertEquals(2, fakeApi.puts)   // 409 then success
        assertEquals(6, result.value.version)
    }

    @Test fun load_reconciles_referenced_blobs() = runBlocking {
        val sh = SessionHolder().apply { set(Session("https://h", "tok", "sha256/x", null)) }
        val vh = VaultKeyHolder().apply { set(ByteArray(32)) }
        val json = """{"v":1,"photos":[{"id":"p1","originalRef":"o1","thumbRef":"t1","metaRef":"m1"}],"albums":[],"people":[]}"""
        val fakeApi = FakeApi(json)
        val fakeUpload = object : de.ledgerline.app.domain.usecase.GalleryUploadApi {
            override suspend fun uploadBytes(bytes: ByteArray, name: String) = Outcome.Ok(UploadedBlob("b", "k", bytes.size.toLong()))
            override suspend fun uploadStream(name: String, size: Long, openInput: () -> java.io.InputStream) = Outcome.Ok(UploadedBlob("b", "k", size))
            override suspend fun process(name: String, mime: String, size: Long, openInput: () -> java.io.InputStream) = throw NotImplementedError()
        }
        val repo = GalleryRepository(sh, vh, fakeCrypto, GalleryCache(), tmpStoreCache(), FakeOfflineFlags(), fakeUpload, de.ledgerline.app.core.offline.DegradedState(), tmpBlobCache(), apiProvider = { fakeApi })

        assertTrue(repo.load() is Outcome.Ok)
        // The living-set POSTed to the server covers every referenced blob (so nothing live is freed).
        assertEquals(setOf("o1", "t1", "m1"), fakeApi.lastReconcile?.toSet())
    }
}
