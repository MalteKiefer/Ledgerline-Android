package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.offline.BlobDiskCache
import de.ledgerline.app.core.offline.OfflineFlags
import androidx.annotation.VisibleForTesting
import de.ledgerline.app.data.offline.ContactBlobPolicy
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.ReconcileRequest
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import javax.inject.Inject
import javax.inject.Singleton

/** Bytes returned/used after the server frees orphaned contact blobs. */
data class ContactUsage(val used: Long, val quota: Long)

/**
 * Streams contact avatar blobs to/from the pinned, authenticated session. Records
 * themselves live in the sealed `/store` manifest; only avatars are separate encrypted
 * blobs. Same pattern as [FileBlobRepository]: encrypt (secretstream + Padmé) on upload,
 * frame-decrypt on download, 429-aware bulk delete.
 */
@Singleton
class ContactBlobRepository @VisibleForTesting internal constructor(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val blobCache: BlobDiskCache,
    private val offlineFlags: OfflineFlags,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        blobCache: BlobDiskCache,
        offlineFlags: OfflineFlags,
    ) : this(sessionHolder, vaultKeyHolder, crypto, blobCache, offlineFlags, { s -> NetworkFactory.create(s.baseUrl, { s.token }, s.spkiPin) })

    /** Cache avatars unless the contacts offline policy is OFF (master switch also required). */
    private fun cachingEnabled() =
        offlineFlags.enabled() && offlineFlags.contactsPolicy() != ContactBlobPolicy.OFF

    /**
     * Fetch an avatar blob's ciphertext into the cache. No decryption, no VK — used by the
     * prefetch engine to pre-populate avatars offline (contacts policy = ALL). Idempotent;
     * skips an already-cached ref.
     */
    suspend fun prefetch(ref: String): Boolean = withContext(Dispatchers.IO) {
        if (blobCache.has(ref)) return@withContext true
        val session = sessionHolder.get() ?: return@withContext false
        try {
            val res = apiProvider(session).contactsRaw(ref)
            if (!res.isSuccessful) return@withContext false
            blobCache.put(ref, res.body()!!.bytes())
            true
        } catch (_: Exception) { false }
    }

    /**
     * Relay a contact birthday/anniversary to the user's enabled notification channels (v1.536). The
     * server intersects the requested [kind] with the user's saved channel prefs and forwards; the
     * app sends only non-secret plaintext (title/body derived on-device). Returns true if forwarded.
     */
    suspend fun notify(kind: String, title: String, body: String): Boolean = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext false
        runCatching {
            apiProvider(session).contactsNotify(de.ledgerline.app.data.remote.dto.ContactNotifyRequest(kind, title, body))
                .takeIf { it.isSuccessful }?.body()?.forwarded ?: false
        }.getOrDefault(false)
    }

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

    /**
     * Fetch + frame-decrypt an avatar blob fully into memory. When offline caching is on,
     * the sealed ciphertext is cached to disk and served from there if the network fails —
     * so avatars stay visible offline (§11). Plaintext never touches disk.
     */
    suspend fun download(ref: String, key: String): Outcome<ByteArray> = withContext(Dispatchers.IO) {
        val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
        // Cache-first: avatar blobs are content-addressed and immutable, so a cache hit is
        // always current — serve it instantly and skip the network entirely.
        if (cachingEnabled()) {
            blobCache.get(ref)?.let { cipher ->
                runCatching { BlobDownloader.decrypt(cipher, key, vk, crypto) }.getOrNull()
                    ?.let { return@withContext Outcome.Ok(it) }
            }
        }
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        try {
            val res = apiProvider(session).contactsRaw(ref)
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            val cipher = res.body()!!.bytes()
            if (cachingEnabled()) blobCache.put(ref, cipher)
            Outcome.Ok(BlobDownloader.decrypt(cipher, key, vk, crypto))
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    /**
     * Delete freed avatar blobs, honoring `Retry-After` on 429 (backoff capped at 30 s,
     * max 3 attempts per blob). Sequential is fine — avatar bulk sizes are tiny.
     */
    suspend fun deleteBlobs(refs: List<String>) = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext
        val api = apiProvider(session)
        deleteBlobsWithBackoff(refs, onRemoveCache = { blobCache.remove(it) }) { api.deleteContactBlob(it) }
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
    // Deferred: orphaned-blob garbage-collection not yet wired (CLAUDE.md §6).
    suspend fun reconcile(referenced: List<String>): Outcome<ContactUsage> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        try {
            val res = apiProvider(session).contactsReconcile(ReconcileRequest(referenced))
            if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.NETWORK)
            val u = res.body()!!
            Outcome.Ok(ContactUsage(u.used, u.quota))
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

}
