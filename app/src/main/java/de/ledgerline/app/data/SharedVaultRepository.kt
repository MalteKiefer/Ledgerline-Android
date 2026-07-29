package de.ledgerline.app.data

import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.crypto.PQKEM
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A cross-user shared-vault membership (the row from `GET /vaults`). */
data class SharedVault(
    val membershipId: Long,
    val vaultId: String,
    val role: String,
    val status: String,      // pending | active | revoked
    val kind: String,        // password | folder
    val owner: Boolean,
    val wrappedVaultKey: String?,
) {
    val canOpen get() = status == "active" && wrappedVaultKey != null
}

/** One file in a folder-vault's decrypted manifest. `key` = per-file content key wrapped under VK_vault. */
data class VaultFile(val ref: String, val key: String, val name: String, val mime: String, val path: String, val size: Long)

/** Decrypted content of a shared vault (`GET /vaults/{vault}/store` opened with VK_vault). */
data class SharedVaultContent(
    val name: String,
    val kind: String,
    val files: List<VaultFile> = emptyList(),
    val secretNames: List<String> = emptyList(),
    val version: Int = 0,
)

/**
 * Recipient/reader side of cross-user shared vaults. The vault key (VK_vault) is delivered
 * PQ-hybrid-wrapped (X25519 + ML-KEM-768) to the caller's published identity and is unwrapped
 * ON-DEVICE with the byte-verified [PQKEM] — the server never sees it. Empty HKDF context (matches
 * the web `unwrapVaultKey(...)` call sites, which pass no context). Read + accept only; owner-side
 * create/invite/rotate is a separate build.
 */
@Singleton
class SharedVaultRepository(
    private val sessionHolder: SessionHolder,
    private val identityRepo: IdentityRepository,
    private val pqkem: PQKEM,
    private val crypto: Crypto,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(
        sessionHolder: SessionHolder,
        identityRepo: IdentityRepository,
        pqkem: PQKEM,
        crypto: Crypto,
    ) : this(sessionHolder, identityRepo, pqkem, crypto, { s -> NetworkFactory.create(s.baseUrl, { s.token }, s.spkiPin) })

    private val json = Json { ignoreUnknownKeys = true }

    /** In-memory unwrapped VK_vault per vault (never persisted; dropped on lock/logout via [clear]). */
    private val vkCache = ConcurrentHashMap<String, ByteArray>()

    suspend fun list(): List<SharedVault> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext emptyList()
        val r = runCatching { apiProvider(session).vaults(null) }.getOrNull()
        if (r?.isSuccessful != true) return@withContext emptyList()
        r.body().orEmpty().map {
            SharedVault(it.id, it.vaultId, it.role, it.status, it.kind, it.owner, it.wrappedVaultKey)
        }
    }

    /** Accept my own pending invitation → active. [member] = my membership id from [list]. */
    suspend fun accept(vaultId: String, membershipId: Long): Boolean = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext false
        runCatching { apiProvider(session).acceptVaultMember(vaultId, membershipId.toString()) }
            .getOrNull()?.isSuccessful == true
    }

    /** Unwrap (and cache) VK_vault from the PQ-hybrid envelope using my identity secrets. */
    private suspend fun vaultKey(v: SharedVault): ByteArray? {
        vkCache[v.vaultId]?.let { return it }
        val wrapped = v.wrappedVaultKey ?: return null
        val id = identityRepo.ensure() ?: return null
        val vk = runCatching { pqkem.hybridUnwrap(wrapped, id.x25519Sk, id.mlkemSeed, "") }.getOrNull() ?: return null
        vkCache[v.vaultId] = vk
        return vk
    }

    /** Unwrap VK, fetch + decrypt the sealed manifest, and parse it by kind. Null on any failure. */
    suspend fun open(v: SharedVault): SharedVaultContent? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        val vk = vaultKey(v) ?: return@withContext null
        val r = runCatching { apiProvider(session).vaultStore(v.vaultId) }.getOrNull()
        if (r?.isSuccessful != true) return@withContext null
        val body = r.body() ?: return@withContext null
        val sealed = body.sealedManifest ?: return@withContext SharedVaultContent("", v.kind, version = body.version)
        val plain = crypto.openManifest(sealed, vk) ?: return@withContext null
        val obj = runCatching { json.parseToJsonElement(plain).jsonObject }.getOrNull() ?: return@withContext null
        parse(obj, v.kind, body.version)
    }

    private fun parse(obj: JsonObject, kind: String, version: Int): SharedVaultContent {
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
        if (kind == "password") {
            val names = (obj["items"]?.jsonArray ?: emptyList()).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                (o["title"] ?: o["name"])?.jsonPrimitive?.contentOrNull
            }
            return SharedVaultContent(name, kind, secretNames = names, version = version)
        }
        val files = (obj["files"]?.jsonArray ?: emptyList()).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            VaultFile(
                ref = s("ref") ?: return@mapNotNull null,
                key = o["key"]?.let { it.jsonPrimitive.contentOrNull ?: it.toString() } ?: return@mapNotNull null,
                name = s("name") ?: return@mapNotNull null,
                mime = s("mime") ?: "application/octet-stream",
                path = s("path") ?: "",
                size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
        return SharedVaultContent(name, kind, files = files, version = version)
    }

    /** Download + decrypt one folder-vault file: unwrap its key under VK_vault, then secretstream-decrypt. */
    suspend fun downloadFile(vaultId: String, file: VaultFile): ByteArray? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        val vk = vkCache[vaultId] ?: return@withContext null
        val fk = crypto.openValue(file.key, vk) ?: return@withContext null
        val r = runCatching { apiProvider(session).vaultBlobRaw(vaultId, file.ref) }.getOrNull()
        if (r?.isSuccessful != true) return@withContext null
        val cipher = r.body()?.bytes() ?: return@withContext null
        runCatching { BlobDownloader.decryptWithKey(cipher, fk, crypto) }.getOrNull()
    }

    /** Drop cached vault keys (on lock / logout). */
    fun clear() = vkCache.clear()
}
