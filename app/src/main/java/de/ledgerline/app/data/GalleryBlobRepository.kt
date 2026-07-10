package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.OfflineFlags
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.ProcessResponse
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.usecase.GalleryBlobs
import de.ledgerline.app.domain.usecase.GalleryUploadApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryBlobRepository private constructor(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val blobCache: BlobDiskCache,
    private val offlineFlags: OfflineFlags,
    private val apiProvider: (Session) -> LedgerlineApi,
) : GalleryBlobs, GalleryUploadApi {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        blobCache: BlobDiskCache,
        offlineFlags: OfflineFlags,
    ) : this(sessionHolder, vaultKeyHolder, crypto, blobCache, offlineFlags, { s -> NetworkFactory.create(s.baseUrl, { s.token }, s.spkiPin) })

    override suspend fun download(ref: String, key: String): Outcome<ByteArray> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        try {
            val res = apiProvider(session).galleryRaw(ref)
            // Non-2xx (except the 401 auth path) may fall back to the ciphertext cache.
            if (!res.isSuccessful) {
                return@withContext if (res.code() == java.net.HttpURLConnection.HTTP_UNAUTHORIZED) {
                    Outcome.Err(ErrorKind.NETWORK)
                } else {
                    cachedOr(ref, key, vk, Outcome.Err(ErrorKind.NETWORK))
                }
            }
            val bytes = res.body()!!.bytes()
            if (offlineFlags.photosBlobs()) blobCache.put(ref, bytes)
            Outcome.Ok(BlobDownloader.decrypt(bytes, key, vk, crypto))
        } catch (e: Exception) { cachedOr(ref, key, vk, Outcome.Err(ErrorKind.DECRYPT, e)) }
    }

    /**
     * Network-first cache fallback for a gallery blob: when the fetch fails, try the
     * on-disk ciphertext cache (if photos caching is on) and decrypt it in-memory.
     * Returns [err] if disabled, absent, or decryption fails.
     */
    private fun cachedOr(ref: String, key: String, vk: ByteArray, err: Outcome<ByteArray>): Outcome<ByteArray> {
        if (!offlineFlags.photosBlobs()) return err
        val bytes = blobCache.get(ref) ?: return err
        return try {
            Outcome.Ok(BlobDownloader.decrypt(bytes, key, vk, crypto))
        } catch (_: Exception) {
            err
        }
    }

    override suspend fun uploadBytes(bytes: ByteArray, name: String): Outcome<UploadedBlob> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val enc = crypto.newContentEncryptor(vk)
        val body = EncryptedUpload.body(enc, crypto.contentChunkSize, bytes.size.toLong()) { java.io.ByteArrayInputStream(bytes) }
        try {
            val part = MultipartBody.Part.createFormData("file", name, body)
            val res = apiProvider(session).galleryUpload(part)
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            Outcome.Ok(UploadedBlob(res.body()!!.id, enc.sealKey(), bytes.size.toLong()))
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    override suspend fun process(bytes: ByteArray, name: String, mime: String): Outcome<ProcessResponse> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        try {
            val requestBody = bytes.toRequestBody(mime.toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", name, requestBody)
            val res = apiProvider(session).galleryProcess(part)
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            Outcome.Ok(res.body()!!)
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    companion object {
        /** Test factory exposing the api-provider seam (the `@Inject` ctor wires the real network). */
        internal fun forTest(
            sessionHolder: SessionHolder,
            vaultKeyHolder: VaultKeyHolder,
            crypto: Crypto,
            blobCache: BlobDiskCache,
            offlineFlags: OfflineFlags,
            api: LedgerlineApi,
        ): GalleryBlobRepository =
            GalleryBlobRepository(sessionHolder, vaultKeyHolder, crypto, blobCache, offlineFlags, { api })
    }
}
