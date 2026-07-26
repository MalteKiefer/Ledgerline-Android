package de.ledgerline.app.data

import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreDiskCache
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.offline.ContactBlobPolicy
import de.ledgerline.app.data.offline.FileBlobPolicy
import de.ledgerline.app.data.offline.PhotoBlobPolicy
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.cleartextApi
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.data.remote.dto.PairClaimRequest
import de.ledgerline.app.data.remote.dto.PairClaimResponse
import de.ledgerline.app.data.remote.dto.PairPollResponse
import de.ledgerline.app.data.remote.dto.ProcessResponse
import de.ledgerline.app.data.remote.dto.StorePutRequest
import de.ledgerline.app.data.remote.dto.StoreResponse
import de.ledgerline.app.data.remote.dto.UploadResponse
import de.ledgerline.app.data.remote.dto.UsageResponse
import de.ledgerline.app.data.remote.dto.VaultResponse
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File

/** JVM test helpers for the offline-cache seam. */

/**
 * A trivial [OfflineFlags] with per-value fields; defaults keep caching on with an
 * unlimited size limit so existing tests read clearly.
 */
class FakeOfflineFlags(
    private val enabled: Boolean = true,
    private val filesPolicy: FileBlobPolicy = FileBlobPolicy.ON_DEMAND,
    private val photosPolicy: PhotoBlobPolicy = PhotoBlobPolicy.ON_DEMAND,
    private val contactsPolicy: ContactBlobPolicy = ContactBlobPolicy.ON_DEMAND,
    private val maxBytes: Long = 0L,
    private val wifiOnly: Boolean = false,
    private val chargingOnly: Boolean = false,
) : OfflineFlags {
    override fun enabled() = enabled
    override fun filesPolicy() = filesPolicy
    override fun photosPolicy() = photosPolicy
    override fun contactsPolicy() = contactsPolicy
    override fun maxBytes() = maxBytes
    override fun wifiOnly() = wifiOnly
    override fun chargingOnly() = chargingOnly
}

/** A [StoreDiskCache] rooted at a fresh temp dir (auto-unique per call). */
fun tmpStoreCache(): StoreDiskCache =
    StoreDiskCache(File(System.getProperty("java.io.tmpdir"), "t-store-" + System.nanoTime()))

// ---- Blob-repo test factories (were `Repo.forTest` in main) ----------------------

/**
 * A [FileBlobRepository] wired to a cleartext api provider so a plain-HTTP MockWebServer
 * can be driven from JVM unit tests. [FileBlobRepository.deleteBlobs] never touches
 * crypto, so the non-throwing [SealTagCrypto] stub is fine (no native libsodium needed);
 * offline flags are off so caching stays inert.
 */
fun fileBlobRepoForTest(baseUrl: String): FileBlobRepository = FileBlobRepository(
    sessionHolder = SessionHolder().apply { set(Session(baseUrl, "tok", "", null)) },
    vaultKeyHolder = VaultKeyHolder().apply { set(ByteArray(32)) },
    crypto = SealTagCrypto(),
    blobCache = tmpBlobCache(),
    offlineFlags = FakeOfflineFlags(enabled = false),
    apiProvider = { s -> cleartextApi(s.baseUrl, tokenProvider = { s.token }) },
)

/** A [GalleryBlobRepository] bound to a fixed fake [api] (the api-provider seam). */
fun galleryBlobRepoForTest(
    sessionHolder: SessionHolder,
    vaultKeyHolder: VaultKeyHolder,
    crypto: Crypto,
    blobCache: BlobDiskCache,
    offlineFlags: OfflineFlags,
    api: LedgerlineApi,
): GalleryBlobRepository =
    GalleryBlobRepository(sessionHolder, vaultKeyHolder, crypto, blobCache, offlineFlags) { api }

/** A [ContactBlobRepository] bound to a fixed fake [api] (the api-provider seam). */
fun contactBlobRepoForTest(
    sessionHolder: SessionHolder,
    vaultKeyHolder: VaultKeyHolder,
    crypto: Crypto,
    blobCache: BlobDiskCache,
    offlineFlags: OfflineFlags,
    api: LedgerlineApi,
): ContactBlobRepository =
    ContactBlobRepository(sessionHolder, vaultKeyHolder, crypto, blobCache, offlineFlags) { api }

/** A [BlobDiskCache] rooted at a fresh temp dir (auto-unique per call). */
fun tmpBlobCache(): BlobDiskCache =
    BlobDiskCache(File(System.getProperty("java.io.tmpdir"), "t-blob-" + System.nanoTime()))

/**
 * A [LedgerlineApi] whose every method throws by default; override just the endpoints
 * a given test exercises. Any accidental call to an un-overridden endpoint is loud.
 */
/** A [de.ledgerline.app.domain.usecase.GalleryUploadApi] whose methods throw — for
 *  gallery tests that never upload (load-path tests). */
object NoGalleryUpload : de.ledgerline.app.domain.usecase.GalleryUploadApi {
    override suspend fun uploadBytes(bytes: ByteArray, name: String) = throw NotImplementedError()
    override suspend fun process(bytes: ByteArray, name: String, mime: String) = throw NotImplementedError()
}

open class NotImplementedApi : LedgerlineApi {
    override suspend fun claimPair(body: PairClaimRequest): Response<PairClaimResponse> = throw NotImplementedError()
    override suspend fun pollPair(body: de.ledgerline.app.data.remote.dto.PairCollectRequest): Response<PairPollResponse> = throw NotImplementedError()
    override suspend fun me(): Response<de.ledgerline.app.data.remote.dto.MeResponse> = throw NotImplementedError()
    override suspend fun passwordsBreach(prefix: String): Response<ResponseBody> = throw NotImplementedError()
    override suspend fun passwordsIcon(domain: String): Response<de.ledgerline.app.data.remote.dto.IconResponse> = throw NotImplementedError()
    override suspend fun passwordsTfaDirectory(): Response<de.ledgerline.app.data.remote.dto.TfaDirectoryResponse> = throw NotImplementedError()
    override suspend fun vault(): Response<VaultResponse> = throw NotImplementedError()
    override suspend fun vaultKeys(): Response<de.ledgerline.app.data.remote.dto.VaultKeysResponse> = throw NotImplementedError()
    override suspend fun putVaultKeys(body: de.ledgerline.app.data.remote.dto.PublishKeysRequest): Response<Unit> = throw NotImplementedError()
    override suspend fun store(): Response<StoreResponse> = throw NotImplementedError()
    override suspend fun moduleStore(module: String): Response<StoreResponse> = throw NotImplementedError()
    override suspend fun putModuleStore(module: String, body: StorePutRequest): Response<StoreResponse> = throw NotImplementedError()
    override suspend fun deleteSession(): Response<Unit> = throw NotImplementedError()
    override suspend fun rawFile(blob: String): Response<ResponseBody> = throw NotImplementedError()
    override suspend fun uploadFile(file: MultipartBody.Part): Response<UploadResponse> = throw NotImplementedError()
    override suspend fun filesUploadInit(body: de.ledgerline.app.data.remote.dto.UploadInitRequest): Response<de.ledgerline.app.data.remote.dto.UploadInitResponse> = throw NotImplementedError()
    override suspend fun filesUploadPart(token: okhttp3.RequestBody, part: okhttp3.RequestBody, chunk: MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.UploadPartResponse> = throw NotImplementedError()
    override suspend fun filesUploadComplete(body: de.ledgerline.app.data.remote.dto.UploadCompleteRequest): Response<UploadResponse> = throw NotImplementedError()
    override suspend fun filesUploadAbort(body: de.ledgerline.app.data.remote.dto.UploadAbortRequest): Response<Unit> = throw NotImplementedError()
    override suspend fun galleryUploadInit(body: de.ledgerline.app.data.remote.dto.UploadInitRequest): Response<de.ledgerline.app.data.remote.dto.UploadInitResponse> = throw NotImplementedError()
    override suspend fun galleryUploadPart(token: okhttp3.RequestBody, part: okhttp3.RequestBody, chunk: MultipartBody.Part): Response<de.ledgerline.app.data.remote.dto.UploadPartResponse> = throw NotImplementedError()
    override suspend fun galleryUploadComplete(body: de.ledgerline.app.data.remote.dto.UploadCompleteRequest): Response<UploadResponse> = throw NotImplementedError()
    override suspend fun galleryUploadAbort(body: de.ledgerline.app.data.remote.dto.UploadAbortRequest): Response<Unit> = throw NotImplementedError()
    override suspend fun deleteBlob(blob: String): Response<Unit> = throw NotImplementedError()
    // Default to an empty sharded files store so WorkspaceRepository.load()'s files slice
    // resolves to an empty list in fakes that don't exercise files. Override where needed.
    override suspend fun filesStore(): Response<StoreResponse> = Response.success(StoreResponse(null, 0))
    override suspend fun filesStorePut(body: StorePutRequest): Response<StoreResponse> = throw NotImplementedError()
    override suspend fun putStore(body: StorePutRequest): Response<StoreResponse> = throw NotImplementedError()
    override suspend fun filesUsage(): Response<UsageResponse> = throw NotImplementedError()
    override suspend fun galleryStore(): Response<StoreResponse> = throw NotImplementedError()
    override suspend fun galleryRaw(blob: String): Response<ResponseBody> = throw NotImplementedError()
    override suspend fun galleryUsage(): Response<UsageResponse> = throw NotImplementedError()
    override suspend fun galleryUpload(file: MultipartBody.Part): Response<UploadResponse> = throw NotImplementedError()
    override suspend fun galleryProcess(file: MultipartBody.Part): Response<ProcessResponse> = throw NotImplementedError()
    override suspend fun galleryStorePut(body: StorePutRequest): Response<StoreResponse> = throw NotImplementedError()
    override suspend fun deleteGalleryBlob(blob: String): Response<Unit> = throw NotImplementedError()
    override suspend fun embedText(body: de.ledgerline.app.data.remote.dto.EmbedTextRequest): Response<de.ledgerline.app.data.remote.dto.EmbedTextResponse> = throw NotImplementedError()
    override suspend fun contactsUsage(): Response<UsageResponse> = throw NotImplementedError()
    override suspend fun contactsReconcile(body: de.ledgerline.app.data.remote.dto.ReconcileRequest): Response<de.ledgerline.app.data.remote.dto.ReconcileResponse> = throw NotImplementedError()
    override suspend fun contactsUpload(file: MultipartBody.Part): Response<UploadResponse> = throw NotImplementedError()
    override suspend fun contactsRaw(blob: String): Response<ResponseBody> = throw NotImplementedError()
    override suspend fun deleteContactBlob(blob: String): Response<Unit> = throw NotImplementedError()
    override suspend fun galleryReverse(lat: Double, lng: Double): Response<de.ledgerline.app.data.remote.dto.ReverseResponse> = throw NotImplementedError()
}

/**
 * Fake crypto for the offline seam: [sealManifest] tags the JSON with `SEALED:`,
 * [openManifest] strips it. Content decryption echoes its input frame (so a blob
 * whose "ciphertext" equals its plaintext round-trips through [BlobDownloader]).
 */
open class SealTagCrypto : Crypto {
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
    override fun u32le(n: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = (n and 0xFF).toByte()
        b[1] = ((n shr 8) and 0xFF).toByte()
        b[2] = ((n shr 16) and 0xFF).toByte()
        b[3] = ((n shr 24) and 0xFF).toByte()
        return b
    }
    override fun readU32le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)
    override fun newContentEncryptor(vk: ByteArray): Crypto.ContentEncryptor = throw NotImplementedError()
    override fun contentDecryptor(encFileKey: String, vk: ByteArray): Crypto.ContentDecryptor = EchoDecryptor()

    /** Header is empty; each frame decrypts to itself, so one frame == the whole plaintext. */
    private class EchoDecryptor : Crypto.ContentDecryptor {
        override val headerBytes = 0
        override fun start(header: ByteArray) {}
        override fun decryptFrame(frame: ByteArray): Pair<ByteArray, Boolean> = frame to true
    }
}

/**
 * Frames [plaintext] the way [BlobDownloader.decrypt] expects from [SealTagCrypto]:
 * empty header, a single `u32le(len) ++ plaintext` frame (which the echo decryptor
 * returns verbatim, marked final).
 */
fun frameForEchoDecrypt(plaintext: ByteArray, crypto: Crypto): ByteArray =
    crypto.u32le(plaintext.size) + plaintext
