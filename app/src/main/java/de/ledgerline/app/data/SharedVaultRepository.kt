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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
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
    private val vaultKeyHolder: de.ledgerline.app.core.security.VaultKeyHolder,
    private val workspaceCache: de.ledgerline.app.core.WorkspaceCache,
    private val mutateWorkspace: de.ledgerline.app.domain.usecase.MutateWorkspace,
    private val apiProvider: (Session) -> LedgerlineApi,
) {
    @Inject constructor(
        sessionHolder: SessionHolder,
        identityRepo: IdentityRepository,
        pqkem: PQKEM,
        crypto: Crypto,
        vaultKeyHolder: de.ledgerline.app.core.security.VaultKeyHolder,
        workspaceCache: de.ledgerline.app.core.WorkspaceCache,
        mutateWorkspace: de.ledgerline.app.domain.usecase.MutateWorkspace,
    ) : this(sessionHolder, identityRepo, pqkem, crypto, vaultKeyHolder, workspaceCache, mutateWorkspace, { s -> NetworkFactory.create(s.baseUrl, { s.token }, s.spkiPin) })

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

    // ---- Owner side: create, invite, member management, rotate ----

    enum class InviteResult { OK, NOT_FOUND, NO_RECIPIENT_KEY, NO_VAULT_KEY, FAILED }

    /**
     * Create a shared vault: a fresh random VK_vault wrapped (PQ-hybrid) to MY OWN identity, then an
     * initial `{name}` manifest sealed under it. Returns the new vault id, or null on failure.
     */
    suspend fun create(kind: String, name: String): String? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        val id = identityRepo.ensure() ?: return@withContext null
        val vk = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = runCatching { pqkem.hybridWrap(vk, id.x25519Pub, id.mlkemEk, "").toJson() }.getOrNull() ?: return@withContext null
        val api = apiProvider(session)
        val cr = runCatching { api.createVault(de.ledgerline.app.data.remote.dto.CreateVaultRequest(wrapped, kind)) }.getOrNull()
        if (cr?.isSuccessful != true) return@withContext null
        val vaultId = cr.body()?.id?.takeIf { it.isNotBlank() } ?: return@withContext null
        vkCache[vaultId] = vk
        // Seal an initial manifest (name + kind-appropriate empty collections) at the store's version.
        val manifest = buildJsonObject {
            put("name", JsonPrimitive(name))
            if (kind == "password") put("items", JsonArray(emptyList()))
            else { put("folders", JsonArray(emptyList())); put("files", JsonArray(emptyList())) }
        }
        val version = runCatching { api.vaultStore(vaultId) }.getOrNull()?.body()?.version ?: 0
        runCatching {
            api.vaultStorePut(vaultId, de.ledgerline.app.data.remote.dto.SharedVaultStorePut(crypto.sealManifest(manifest.toString(), vk), version))
        }
        vaultId
    }

    suspend fun members(vaultId: String): List<de.ledgerline.app.data.remote.dto.VaultMemberDto> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext emptyList()
        val r = runCatching { apiProvider(session).vaultMembers(vaultId) }.getOrNull()
        if (r?.isSuccessful == true) r.body().orEmpty() else emptyList()
    }

    /** Resolve a recipient + wrap VK_vault to them + POST the membership. Uniform NOT_FOUND on 422. */
    suspend fun invite(vault: SharedVault, identifier: String, role: String): InviteResult = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext InviteResult.FAILED
        val vk = vaultKey(vault) ?: return@withContext InviteResult.NO_VAULT_KEY
        val api = apiProvider(session)
        val rr = runCatching { api.resolveRecipient(vault.vaultId, de.ledgerline.app.data.remote.dto.ResolveRecipientRequest(identifier)) }.getOrNull()
        if (rr?.isSuccessful != true) return@withContext InviteResult.NOT_FOUND
        val r = rr.body() ?: return@withContext InviteResult.NOT_FOUND
        val pub = r.publicKey?.let { crypto.b64decode(it) } ?: return@withContext InviteResult.NO_RECIPIENT_KEY
        val ek = r.mlkemPublicKey?.let { crypto.b64decode(it) } ?: return@withContext InviteResult.NO_RECIPIENT_KEY
        val wrapped = runCatching { pqkem.hybridWrap(vk, pub, ek, "").toJson() }.getOrNull() ?: return@withContext InviteResult.FAILED
        val ok = runCatching {
            api.addVaultMember(vault.vaultId, de.ledgerline.app.data.remote.dto.AddMemberRequest(r.userId, role, wrapped, r.fingerprint))
        }.getOrNull()?.isSuccessful == true
        if (ok) InviteResult.OK else InviteResult.FAILED
    }

    suspend fun updateMemberRole(vaultId: String, memberId: Long, role: String): Boolean = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext false
        runCatching {
            apiProvider(session).updateVaultMember(vaultId, memberId.toString(), de.ledgerline.app.data.remote.dto.UpdateMemberRequest(role))
        }.getOrNull()?.isSuccessful == true
    }

    /**
     * Securely remove a member: rotate VK_vault (fresh key, re-seal the manifest, re-wrap to all
     * REMAINING active members) in one atomic call — the cryptographic revocation step. The removed
     * member's cached old VK can no longer decrypt future writes.
     */
    suspend fun removeMemberAndRotate(vault: SharedVault, removeMemberId: Long): Boolean = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext false
        val oldVk = vaultKey(vault) ?: return@withContext false
        val api = apiProvider(session)
        val storeR = runCatching { api.vaultStore(vault.vaultId) }.getOrNull()
        if (storeR?.isSuccessful != true) return@withContext false
        val store = storeR.body() ?: return@withContext false
        // Re-seal the CURRENT manifest verbatim under a fresh VK (open with old, seal with new — no re-parse).
        val rawJson = store.sealedManifest?.let { crypto.openManifest(it, oldVk) } ?: "{\"name\":\"\"}"
        val newVk = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sealed = crypto.sealManifest(rawJson, newVk)
        val remaining = members(vault.vaultId).filter {
            it.status == "active" && it.id != removeMemberId && it.publicKey != null && it.mlkemPublicKey != null
        }
        val wraps = remaining.mapNotNull { m ->
            val pub = crypto.b64decode(m.publicKey!!); val ek = crypto.b64decode(m.mlkemPublicKey!!)
            runCatching { de.ledgerline.app.data.remote.dto.RotateMember(m.userId, pqkem.hybridWrap(newVk, pub, ek, "").toJson()) }.getOrNull()
        }
        val ok = runCatching {
            api.rotateVault(vault.vaultId, de.ledgerline.app.data.remote.dto.RotateRequest(sealed, store.version, removeMemberId, wraps))
        }.getOrNull()?.isSuccessful == true
        if (ok) vkCache[vault.vaultId] = newVk
        ok
    }

    // ---- Private → shared folder conversion (ZK; the server never re-encrypts) ----

    data class ConvertResult(val vaultId: String, val movedFiles: Int)

    /**
     * Convert a PERSONAL folder subtree into a new shared folder-vault (openapi `/vaults` §note):
     * create a folder-vault (fresh VK_vault), then for each file re-wrap its per-file key under
     * VK_vault and re-upload the UNCHANGED ciphertext to the vault blob store, seal a `{name,folders,
     * files}` manifest under VK_vault, and finally drop the moved files+folders from the personal
     * files index. The content ciphertext never leaves the client decrypted; only per-file keys are
     * re-wrapped (VK → VK_vault). Orphaned personal blobs are reclaimed by files reconcile-on-load.
     * Returns the new vault id + moved-file count, or null on any failure (nothing is removed then).
     */
    suspend fun convertFolder(folderId: String, name: String): ConvertResult? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        val personalVk = vaultKeyHolder.get() ?: return@withContext null
        val manifest = workspaceCache.value.value?.manifest ?: return@withContext null
        val folders = manifest.fileFolders
        val subtreeIds = de.ledgerline.app.domain.share.ShareManifests.subtree(folderId, folders)
        val byId = folders.associateBy { it.id }
        val filesToMove = manifest.files.filter { !it.trashed && it.folder != null && it.folder in subtreeIds }

        // 1. Create the folder-vault (fresh VK_vault, cached) + empty manifest.
        val vaultId = create("folder", name) ?: return@withContext null
        val vk = vkCache[vaultId] ?: return@withContext null
        val api = apiProvider(session)

        // 2. Move each file: re-wrap key under VK_vault, re-upload unchanged ciphertext.
        val fileEntries = ArrayList<JsonObject>()
        for (f in filesToMove) {
            if (f.blob.isBlank() || f.encFileKey.isBlank()) continue
            val fk = crypto.openValue(f.encFileKey, personalVk) ?: return@withContext null
            val cipher = runCatching {
                val r = api.rawFile(f.blob); if (r.isSuccessful) r.body()?.bytes() else null
            }.getOrNull() ?: return@withContext null
            val reqBody = cipher.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            val body = okhttp3.MultipartBody.Part.createFormData("file", "blob.enc", reqBody)
            val newRef = runCatching { api.vaultBlobUpload(vaultId, body) }.getOrNull()?.takeIf { it.isSuccessful }?.body()?.id
                ?: return@withContext null
            val newKey = crypto.sealValue(fk, vk)
            val path = de.ledgerline.app.domain.share.ShareManifests.relPath(f.folder, folderId, byId)
            fileEntries += buildJsonObject {
                put("ref", JsonPrimitive(newRef)); put("key", JsonPrimitive(newKey))
                put("name", JsonPrimitive(f.name)); put("mime", JsonPrimitive(f.mime.ifBlank { "application/octet-stream" }))
                if (path.isNotBlank()) put("path", JsonPrimitive(path))
            }
        }

        // 3. Subfolders (excluding the root; the root becomes the vault). Root's direct children reparent to null.
        val folderEntries = folders.filter { it.id in subtreeIds && it.id != folderId }.map { fo ->
            buildJsonObject {
                put("id", JsonPrimitive(fo.id)); put("name", JsonPrimitive(fo.name))
                put("parent", if (fo.parent == folderId || fo.parent == null) JsonNull else JsonPrimitive(fo.parent!!))
            }
        }

        // 4. Seal + PUT the populated manifest at the store's current version.
        val vaultManifest = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("folders", JsonArray(folderEntries))
            put("files", JsonArray(fileEntries))
        }
        val version = runCatching { api.vaultStore(vaultId) }.getOrNull()?.body()?.version ?: 0
        val put = runCatching {
            api.vaultStorePut(vaultId, de.ledgerline.app.data.remote.dto.SharedVaultStorePut(crypto.sealManifest(vaultManifest.toString(), vk), version))
        }.getOrNull()
        if (put?.isSuccessful != true) return@withContext null

        // 5. Remove the moved files + folders (incl. the root) from the PERSONAL index.
        val movedFileIds = filesToMove.map { it.id }.toSet()
        val removeFolderIds = subtreeIds
        mutateWorkspace.invoke { m ->
            m.copy(
                files = m.files.filterNot { it.id in movedFileIds },
                fileFolders = m.fileFolders.filterNot { it.id in removeFolderIds },
            )
        }
        ConvertResult(vaultId, filesToMove.size)
    }

    /**
     * Create a shared PASSWORD-vault pre-populated with [items] (already web-shaped secret JSON).
     * Secrets carry no content blobs (opaque `fields` inline), so unlike folders there is nothing to
     * re-upload — the items are just sealed into the vault manifest under VK_vault. Returns the new
     * vault id, or null on failure (caller then leaves the personal store untouched).
     */
    suspend fun createPasswordVault(name: String, items: List<JsonObject>): String? = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext null
        val vaultId = create("password", name) ?: return@withContext null
        val vk = vkCache[vaultId] ?: return@withContext null
        val manifest = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("items", JsonArray(items))
        }
        val api = apiProvider(session)
        val version = runCatching { api.vaultStore(vaultId) }.getOrNull()?.body()?.version ?: 0
        val put = runCatching {
            api.vaultStorePut(vaultId, de.ledgerline.app.data.remote.dto.SharedVaultStorePut(crypto.sealManifest(manifest.toString(), vk), version))
        }.getOrNull()
        if (put?.isSuccessful == true) vaultId else null
    }

    /** Drop cached vault keys (on lock / logout). */
    fun clear() = vkCache.clear()
}
