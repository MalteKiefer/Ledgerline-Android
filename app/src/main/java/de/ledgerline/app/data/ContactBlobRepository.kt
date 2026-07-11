package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.ReconcileRequest
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import javax.inject.Inject
import javax.inject.Singleton

/** Bytes returned/used after the server frees orphaned contact blobs. */
data class ContactUsage(val used: Long, val quota: Long)

/**
 * Streams contact avatar blobs to/from the pinned, authenticated session. Records
 * themselves live in the sealed `/store` manifest; only avatars are separate encrypted
 * blobs. Mirrors [GalleryBlobRepository]: encrypt (secretstream + Padmé) on upload,
 * frame-decrypt on download, 429-aware bulk delete.
 */
@Singleton
class ContactBlobRepository private constructor(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
    ) : this(sessionHolder, vaultKeyHolder, crypto, { s -> NetworkFactory.create(s.baseUrl, { s.token }, s.spkiPin) })

    /** Encrypt [bytes] with a fresh per-blob key, Padmé-pad, and upload the avatar. */
    suspend fun uploadAvatar(bytes: ByteArray): Outcome<UploadedBlob> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        val enc = crypto.newContentEncryptor(vk)
        val body = EncryptedUpload.body(enc, crypto.contentChunkSize, bytes.size.toLong()) { java.io.ByteArrayInputStream(bytes) }
        try {
            val part = MultipartBody.Part.createFormData("file", "avatar.jpg", body)
            val res = apiProvider(session).contactsUpload(part)
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            Outcome.Ok(UploadedBlob(res.body()!!.id, enc.sealKey(), bytes.size.toLong()))
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    /** Fetch + frame-decrypt an avatar blob fully into memory. */
    suspend fun download(ref: String, key: String): Outcome<ByteArray> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        try {
            val res = apiProvider(session).contactsRaw(ref)
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            Outcome.Ok(BlobDownloader.decrypt(res.body()!!.bytes(), key, vk, crypto))
        } catch (e: Exception) { Outcome.Err(ErrorKind.DECRYPT, e) }
    }

    /**
     * Delete freed avatar blobs, honoring `Retry-After` on 429 (backoff capped at 30 s,
     * max 3 attempts per blob). Sequential is fine — avatar bulk sizes are tiny.
     */
    suspend fun deleteBlobs(refs: List<String>) = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext
        val api = apiProvider(session)
        for (ref in refs.filter { it.isNotBlank() }.distinct()) {
            var attempt = 0
            while (attempt < 3) {
                val res = try { api.deleteContactBlob(ref) } catch (_: Exception) { break }
                if (res.code() == 429) {
                    val retryAfterMs = res.headers()["Retry-After"]?.toLongOrNull()?.times(1000)
                        ?: (1000L shl attempt)
                    delay(minOf(retryAfterMs, 30_000L))
                    attempt++
                } else {
                    break
                }
            }
        }
    }

    suspend fun usage(): Outcome<ContactUsage> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        try {
            val res = apiProvider(session).contactsUsage()
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            val u = res.body()!!
            Outcome.Ok(ContactUsage(u.used, u.quota))
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    /** Free contact blobs not in [referenced] (all avatar refs the manifest still points at). */
    suspend fun reconcile(referenced: List<String>): Outcome<ContactUsage> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        try {
            val res = apiProvider(session).contactsReconcile(ReconcileRequest(referenced))
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            val u = res.body()!!
            Outcome.Ok(ContactUsage(u.used, u.quota))
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    companion object {
        internal fun forTest(
            sessionHolder: SessionHolder,
            vaultKeyHolder: VaultKeyHolder,
            crypto: Crypto,
            api: LedgerlineApi,
        ): ContactBlobRepository =
            ContactBlobRepository(sessionHolder, vaultKeyHolder, crypto, { api })
    }
}
