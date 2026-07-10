package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.offline.StoreDiskCache
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
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File

/** JVM test helpers for the offline-cache seam. */

/** A trivial [OfflineFlags] with per-flag values; defaults to everything on. */
class FakeOfflineFlags(
    private val enabled: Boolean = true,
    private val filesBlobs: Boolean = true,
    private val photosBlobs: Boolean = true,
) : OfflineFlags {
    override fun enabled() = enabled
    override fun filesBlobs() = filesBlobs
    override fun photosBlobs() = photosBlobs
}

/** A [StoreDiskCache] rooted at a fresh temp dir (auto-unique per call). */
fun tmpStoreCache(): StoreDiskCache =
    StoreDiskCache(File(System.getProperty("java.io.tmpdir"), "t-store-" + System.nanoTime()))

/** A [BlobDiskCache] rooted at a fresh temp dir (auto-unique per call). */
fun tmpBlobCache(): BlobDiskCache =
    BlobDiskCache(File(System.getProperty("java.io.tmpdir"), "t-blob-" + System.nanoTime()))

/**
 * A [LedgerlineApi] whose every method throws by default; override just the endpoints
 * a given test exercises. Any accidental call to an un-overridden endpoint is loud.
 */
open class NotImplementedApi : LedgerlineApi {
    override suspend fun claimPair(body: PairClaimRequest): Response<PairClaimResponse> = throw NotImplementedError()
    override suspend fun pollPair(code: String): Response<PairPollResponse> = throw NotImplementedError()
    override suspend fun vault(): Response<VaultResponse> = throw NotImplementedError()
    override suspend fun store(): Response<StoreResponse> = throw NotImplementedError()
    override suspend fun deleteSession(): Response<Unit> = throw NotImplementedError()
    override suspend fun rawFile(blob: String): Response<ResponseBody> = throw NotImplementedError()
    override suspend fun uploadFile(file: MultipartBody.Part): Response<UploadResponse> = throw NotImplementedError()
    override suspend fun deleteBlob(blob: String): Response<Unit> = throw NotImplementedError()
    override suspend fun putStore(body: StorePutRequest): Response<StoreResponse> = throw NotImplementedError()
    override suspend fun filesUsage(): Response<UsageResponse> = throw NotImplementedError()
    override suspend fun galleryStore(): Response<StoreResponse> = throw NotImplementedError()
    override suspend fun galleryRaw(blob: String): Response<ResponseBody> = throw NotImplementedError()
    override suspend fun galleryUsage(): Response<UsageResponse> = throw NotImplementedError()
    override suspend fun galleryUpload(file: MultipartBody.Part): Response<UploadResponse> = throw NotImplementedError()
    override suspend fun galleryProcess(file: MultipartBody.Part): Response<ProcessResponse> = throw NotImplementedError()
    override suspend fun galleryStorePut(body: StorePutRequest): Response<StoreResponse> = throw NotImplementedError()
}

/**
 * Fake crypto for the offline seam: [sealManifest] tags the JSON with `SEALED:`,
 * [openManifest] strips it. Content decryption echoes its input frame (so a blob
 * whose "ciphertext" equals its plaintext round-trips through [BlobDownloader]).
 */
open class SealTagCrypto : Crypto {
    override fun sealManifest(json: String, vk: ByteArray) = "SEALED:$json"
    override fun openManifest(ciphertext: String, vk: ByteArray) = ciphertext.removePrefix("SEALED:")
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
