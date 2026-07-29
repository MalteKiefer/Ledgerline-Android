package de.ledgerline.app.data

import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.crypto.ShareCrypto
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.ShareMetaResponse
import de.ledgerline.app.data.remote.dto.ShareUnlockRequest
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** A parsed `{baseUrl}/s/{token}#s:{sk}` share link. */
data class ParsedShareLink(val token: String, val shareKey: String, val host: String)

/** A decrypted (SK-opened) share manifest — either a file/folder bundle or a gallery album. */
data class SharedManifest(
    val kind: String,                 // file | folder | gallery
    val name: String,
    val allowDownload: Boolean,
    val files: List<SharedFile> = emptyList(),
    val photos: List<SharedPhoto> = emptyList(),
)

data class SharedFile(val name: String, val mime: String, val size: Long, val path: String, val ref: String, val key: String)

data class SharedPhoto(
    val id: String,
    val type: String,
    val caption: String,
    val thumbRef: String? = null, val thumbKey: String? = null,
    val mediumRef: String? = null, val mediumKey: String? = null,
    val originalRef: String? = null, val originalKey: String? = null,
) {
    /** Best display rendition ref/key (medium → thumb). */
    val displayRef get() = mediumRef ?: thumbRef
    val displayKey get() = mediumKey ?: thumbKey
}

/**
 * Recipient side of the public share links this app already CREATES: opens `{baseUrl}/s/{token}#s:{sk}`
 * in-app. The share key (SK) rides in the URL fragment and never touches the server; the sealed
 * manifest + blobs are decrypted on-device with it — no vault key needed (a share is independent of
 * the recipient's own vault). Scoped to the CONNECTED server (same host as the session) — a link to a
 * different self-hosted instance is left to the browser (no SPKI pin for an arbitrary host).
 */
@Singleton
class SharedLinkRepository(
    private val sessionHolder: SessionHolder,
    private val shareCrypto: ShareCrypto,
    private val crypto: Crypto,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(sessionHolder: SessionHolder, shareCrypto: ShareCrypto, crypto: Crypto) : this(
        sessionHolder, shareCrypto, crypto,
        { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Parse a share URL into token + SK + host, or null if it isn't a `…/s/{token}#s:{sk}` link. */
    fun parse(url: String): ParsedShareLink? {
        val u = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        val segments = (u.path ?: "").split('/').filter { it.isNotBlank() }
        val sIdx = segments.indexOf("s")
        if (sIdx < 0 || sIdx + 1 >= segments.size) return null
        val token = segments[sIdx + 1]
        val frag = u.rawFragment ?: url.substringAfter('#', "")
        val sk = frag.substringAfter("s:", "").takeIf { it.isNotBlank() } ?: return null
        val host = u.host ?: return null
        return ParsedShareLink(token, java.net.URLDecoder.decode(sk, "UTF-8"), host)
    }

    /** True when the link points at the connected server (the only host we can pin + call in-app). */
    fun isOwnServer(host: String): Boolean {
        val base = sessionHolder.get()?.baseUrl ?: return false
        return runCatching { java.net.URI(base).host }.getOrNull()?.equals(host, ignoreCase = true) == true
    }

    suspend fun meta(token: String): ShareMetaResponse? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        runCatching { apiProvider(session).shareMeta(token) }.getOrNull()?.takeIf { it.isSuccessful }?.body()
    }

    /** Exchange a password for a grant. Returns null on wrong password / failure. */
    suspend fun unlock(token: String, password: String): String? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        val r = runCatching { apiProvider(session).shareUnlock(token, ShareUnlockRequest(password)) }.getOrNull()
        if (r?.isSuccessful == true && r.body()?.ok == true) r.body()?.grant else null
    }

    /** Fetch the sealed manifest and open it with the SK from the URL fragment. */
    suspend fun manifest(link: ParsedShareLink, grant: String?): SharedManifest? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        val r = runCatching { apiProvider(session).shareManifest(link.token, grant) }.getOrNull()
        if (r?.isSuccessful != true) return@withContext null
        val body = r.body() ?: return@withContext null
        val plain = shareCrypto.openManifest(body.sealed, link.shareKey) ?: return@withContext null
        val obj = runCatching { json.parseToJsonElement(plain).jsonObject }.getOrNull() ?: return@withContext null
        parseManifest(obj, body.allow_download)
    }

    private fun parseManifest(obj: JsonObject, allowDownload: Boolean): SharedManifest {
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
            ?: if (obj.containsKey("photos")) "gallery" else "file"
        if (kind == "gallery") {
            val photos = (obj["photos"]?.jsonArray ?: emptyList()).mapNotNull { el ->
                val p = el as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = p[k]?.jsonPrimitive?.contentOrNull
                SharedPhoto(
                    id = s("id") ?: return@mapNotNull null,
                    type = s("t") ?: "image",
                    caption = s("cap") ?: "",
                    thumbRef = s("tR"), thumbKey = s("tK"),
                    mediumRef = s("mR"), mediumKey = s("mK"),
                    originalRef = s("oR"), originalKey = s("oK"),
                )
            }
            return SharedManifest("gallery", name, obj["allowDownload"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: allowDownload, photos = photos)
        }
        val files = (obj["files"]?.jsonArray ?: emptyList()).mapNotNull { el ->
            val f = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = f[k]?.jsonPrimitive?.contentOrNull
            SharedFile(
                name = s("name") ?: return@mapNotNull null,
                mime = s("mime") ?: "application/octet-stream",
                size = f["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                path = s("path") ?: "",
                ref = s("ref") ?: return@mapNotNull null,
                key = f["key"]?.let { it.jsonPrimitive.contentOrNull ?: it.toString() } ?: return@mapNotNull null,
            )
        }
        return SharedManifest(kind, name, allowDownload, files = files)
    }

    /**
     * Download + decrypt one shared blob: fetch the ciphertext, unwrap the per-blob key with the SK
     * (NOT the vault), and secretstream-decrypt. [wrappedKey] is the manifest's `{"c","n"}` key string.
     */
    suspend fun downloadBlob(token: String, ref: String, wrappedKey: String, shareKey: String, grant: String?): ByteArray? =
        withContext(Dispatchers.IO) {
            val session = sessionHolder.get() ?: return@withContext null
            val fk = shareCrypto.unwrapFileKey(wrappedKey, shareKey) ?: return@withContext null
            val r = runCatching { apiProvider(session).shareBlob(token, ref, grant) }.getOrNull()
            if (r?.isSuccessful != true) return@withContext null
            val cipher = r.body()?.bytes() ?: return@withContext null
            runCatching { BlobDownloader.decryptWithKey(cipher, fk, crypto) }.getOrNull()
        }
}
