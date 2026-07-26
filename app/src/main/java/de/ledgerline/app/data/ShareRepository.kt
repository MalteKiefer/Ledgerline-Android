package de.ledgerline.app.data

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.crypto.ShareCrypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.remote.LedgerlineApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.data.remote.dto.ShareCreateRequest
import de.ledgerline.app.domain.model.GalleryManifest
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.model.ShareInfo
import de.ledgerline.app.domain.model.WorkspaceManifest
import de.ledgerline.app.domain.share.ShareManifests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Owner-side options for a public share. Files always allow download; gallery is a choice. */
data class ShareOptions(
    val allowDownload: Boolean = true,
    val expiresAtIso: String? = null,
    val password: String? = null,
)

/** The created share: its public [token], the fragment [sk] (base64), and the full [link]. */
data class ShareResult(val token: String, val sk: String, val link: String)

/** File/folder public-share surface consumed by the files UI (narrowed for testability). */
interface FileSharing {
    fun existingLink(share: ShareInfo?): String?
    suspend fun createFileShare(id: String, isFolder: Boolean, opts: ShareOptions): Outcome<ShareResult>
    suspend fun updateFileShare(id: String, isFolder: Boolean, opts: ShareOptions): Outcome<ShareResult>
    suspend fun revokeFileShare(id: String, isFolder: Boolean): Outcome<Unit>
}

/** Gallery-album public-share surface consumed by the gallery UI. */
interface AlbumSharing {
    fun existingLink(share: ShareInfo?): String?
    suspend fun createAlbumShare(albumId: String, opts: ShareOptions): Outcome<ShareResult>
    suspend fun updateAlbumShare(albumId: String, opts: ShareOptions): Outcome<ShareResult>
    suspend fun revokeAlbumShare(albumId: String): Outcome<Unit>
}

/**
 * Creates/revokes public share links for files, folders and gallery albums (rebuild-spec
 * §4.4/§4.5). The share key (SK) is generated on-device and lives ONLY in the link fragment
 * (`#s:<sk>`); the server stores just the sealed manifest + blob refs. The SK + token are
 * persisted on the owner's record (via the sealed store) so the link can be re-copied/revoked.
 *
 * All crypto is byte-compatible with the web `ShareCrypto` (see [ShareManifests]).
 */
@Singleton
class ShareRepository(
    private val sessionHolder: SessionHolder,
    private val vaultKeyHolder: VaultKeyHolder,
    private val crypto: Crypto,
    private val shareCrypto: ShareCrypto,
    private val workspaceCache: WorkspaceCache,
    private val galleryCache: GalleryCache,
    private val workspaceRepo: WorkspaceRepository,
    private val galleryRepo: GalleryRepository,
    private val apiProvider: (Session) -> LedgerlineApi,
) : FileSharing, AlbumSharing {
    @Inject constructor(
        sessionHolder: SessionHolder,
        vaultKeyHolder: VaultKeyHolder,
        crypto: Crypto,
        shareCrypto: ShareCrypto,
        workspaceCache: WorkspaceCache,
        galleryCache: GalleryCache,
        workspaceRepo: WorkspaceRepository,
        galleryRepo: GalleryRepository,
    ) : this(
        sessionHolder, vaultKeyHolder, crypto, shareCrypto, workspaceCache, galleryCache,
        workspaceRepo, galleryRepo,
        apiProvider = { s -> NetworkFactory.create(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin) },
    )

    private fun linkFor(baseUrl: String, token: String, sk: String) =
        "${baseUrl.trimEnd('/')}/s/$token#s:$sk"

    /** Reconstruct the public link for an already-created share (token + sk from the record). */
    override fun existingLink(share: ShareInfo?): String? {
        val session = sessionHolder.get() ?: return null
        val info = share ?: return null
        if (info.token.isBlank() || info.sk.isBlank()) return null
        return linkFor(session.baseUrl, info.token, info.sk)
    }

    // ---- Files / folders -------------------------------------------------------

    /** A built file/folder share manifest: the display [name], sealed manifest, and blob refs. */
    private class FileBuild(val name: String, val sealed: String, val refs: List<String>)

    /** Build + seal the file/folder manifest under [sk]; null if the record is gone or empty. */
    private fun buildFileShare(manifest: WorkspaceManifest, id: String, isFolder: Boolean, sk: String, vk: ByteArray): FileBuild? {
        val kind = if (isFolder) "folder" else "file"
        val refs = ArrayList<String>()
        val entries = ArrayList<ShareManifests.FileEntryIn>()
        val name: String
        if (isFolder) {
            val folder = manifest.fileFolders.find { it.id == id } ?: return null
            name = folder.name
            val set = ShareManifests.subtree(id, manifest.fileFolders)
            val byId = manifest.fileFolders.associateBy { it.id }
            for (f in manifest.files) {
                if (f.trashed || f.folder !in set) continue
                addFileEntry(f, ShareManifests.relPath(f.folder, id, byId), sk, vk, entries, refs)
            }
        } else {
            val f = manifest.files.find { it.id == id } ?: return null
            name = f.name
            addFileEntry(f, "", sk, vk, entries, refs)
        }
        if (refs.isEmpty()) return null
        return FileBuild(name, shareCrypto.sealManifest(ShareManifests.fileManifest(kind, name, entries), sk), refs.distinct())
    }

    /** Share a single file or an entire folder subtree. [isFolder] selects the kind. */
    override suspend fun createFileShare(id: String, isFolder: Boolean, opts: ShareOptions): Outcome<ShareResult> =
        withContext(Dispatchers.IO) {
            val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
            val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
            val manifest = currentWorkspace() ?: return@withContext Outcome.Err(ErrorKind.NETWORK)

            val kind = if (isFolder) "folder" else "file"
            val sk = shareCrypto.newShareKey()
            val build = buildFileShare(manifest, id, isFolder, sk, vk) ?: return@withContext Outcome.Err(ErrorKind.HTTP)
            val token = try {
                val res = apiProvider(session).createFileShare(
                    ShareCreateRequest(kind, build.sealed, build.refs, allowDownload = true, expiresAt = opts.expiresAtIso, password = opts.password?.trim()?.ifBlank { null }),
                )
                if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.HTTP)
                res.body()?.token ?: return@withContext Outcome.Err(ErrorKind.NETWORK)
            } catch (e: Exception) {
                return@withContext Outcome.Err(ErrorKind.NETWORK, e)
            }

            val info = ShareInfo(
                token = token, sk = sk, kind = kind,
                hasPassword = !opts.password.isNullOrBlank(),
                expiresAt = opts.expiresAtIso, created = nowIso(),
            )
            val saved = workspaceRepo.save { m -> applyFileShare(m, id, isFolder, info) }
            if (saved is Outcome.Err) return@withContext saved
            Outcome.Ok(ShareResult(token, sk, linkFor(session.baseUrl, token, sk)))
        }

    /**
     * Re-push an existing file/folder share (same token + share key): rebuilds the manifest from
     * the current contents and updates the options. A blank password keeps an existing one; a new
     * one replaces it; `clear_password` is sent only when there was and is no password (web parity).
     */
    override suspend fun updateFileShare(id: String, isFolder: Boolean, opts: ShareOptions): Outcome<ShareResult> =
        withContext(Dispatchers.IO) {
            val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
            val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
            val manifest = currentWorkspace() ?: return@withContext Outcome.Err(ErrorKind.NETWORK)
            val existing = (if (isFolder) manifest.fileFolders.find { it.id == id }?.share
            else manifest.files.find { it.id == id }?.share)
                ?: return@withContext Outcome.Err(ErrorKind.HTTP)

            val sk = existing.sk
            val build = buildFileShare(manifest, id, isFolder, sk, vk) ?: return@withContext Outcome.Err(ErrorKind.HTTP)
            val newPassword = opts.password?.trim()?.ifBlank { null }
            val clearPassword = if (newPassword == null && !existing.hasPassword) true else null
            try {
                val res = apiProvider(session).updateFileShare(
                    existing.token,
                    de.ledgerline.app.data.remote.dto.ShareUpdateRequest(build.sealed, build.refs, allowDownload = true, expiresAt = opts.expiresAtIso, password = newPassword, clearPassword = clearPassword),
                )
                if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.HTTP)
            } catch (e: Exception) {
                return@withContext Outcome.Err(ErrorKind.NETWORK, e)
            }

            val info = existing.copy(
                expiresAt = opts.expiresAtIso,
                hasPassword = when { newPassword != null -> true; clearPassword == true -> false; else -> existing.hasPassword },
            )
            val saved = workspaceRepo.save { m -> applyFileShare(m, id, isFolder, info) }
            if (saved is Outcome.Err) return@withContext saved
            Outcome.Ok(ShareResult(existing.token, sk, linkFor(session.baseUrl, existing.token, sk)))
        }

    /** Revoke a file/folder share: DELETE on the server + clear the owner-side record. */
    override suspend fun revokeFileShare(id: String, isFolder: Boolean): Outcome<Unit> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val manifest = currentWorkspace() ?: return@withContext Outcome.Err(ErrorKind.NETWORK)
        val token = (if (isFolder) manifest.fileFolders.find { it.id == id }?.share
        else manifest.files.find { it.id == id }?.share)?.token
            ?: return@withContext Outcome.Ok(Unit)
        try { apiProvider(session).deleteFileShare(token) } catch (_: Exception) { /* best effort */ }
        val saved = workspaceRepo.save { m -> applyFileShare(m, id, isFolder, null) }
        if (saved is Outcome.Err) saved else Outcome.Ok(Unit)
    }

    private fun applyFileShare(m: WorkspaceManifest, id: String, isFolder: Boolean, info: ShareInfo?): WorkspaceManifest =
        if (isFolder) m.copy(fileFolders = m.fileFolders.map { if (it.id == id) it.copy(share = info) else it })
        else m.copy(files = m.files.map { if (it.id == id) it.copy(share = info) else it })

    private fun addFileEntry(
        f: de.ledgerline.app.domain.model.FileEntry,
        path: String,
        sk: String,
        vk: ByteArray,
        out: MutableList<ShareManifests.FileEntryIn>,
        refs: MutableList<String>,
    ) {
        if (f.blob.isEmpty() || f.encFileKey.isEmpty()) return
        val rawFk = crypto.openValue(f.encFileKey, vk) ?: return
        out.add(
            ShareManifests.FileEntryIn(
                name = f.name, mime = f.mime, size = f.size, path = path,
                ref = f.blob, key = shareCrypto.wrapFileKey(rawFk, sk),
            ),
        )
        refs.add(f.blob)
    }

    // ---- Gallery albums --------------------------------------------------------

    /** Build + seal an album manifest under [sk]; null if the album is gone or has no blobs. */
    private fun buildAlbumShare(manifest: GalleryManifest, albumId: String, sk: String, vk: ByteArray, allowDownload: Boolean): Pair<String, List<String>>? {
        val album = manifest.albums.find { it.id == albumId } ?: return null
        val refs = ArrayList<String>()
        val byId = manifest.photos.associateBy { it.id }
        val entries = album.photoIds.mapNotNull { pid ->
            val p = byId[pid] ?: return@mapNotNull null
            if (p.trashed) return@mapNotNull null
            val blobs = ArrayList<ShareManifests.BlobPair>()
            fun add(ref: String?, key: String?, outRef: String, outKey: String) {
                if (ref.isNullOrEmpty() || key.isNullOrEmpty()) return
                val rawFk = crypto.openValue(key, vk) ?: return
                blobs.add(ShareManifests.BlobPair(outRef, outKey, ref, shareCrypto.wrapFileKey(rawFk, sk)))
                refs.add(ref)
            }
            add(p.thumbRef, p.thumbKey, "tR", "tK")
            add(p.mediumRef, p.mediumKey, "mR", "mK")
            add(p.motionRef, p.motionKey, "moR", "moK")
            if (allowDownload) add(p.originalRef, p.originalKey, "oR", "oK")
            ShareManifests.PhotoEntryIn(
                id = p.id, type = p.media_type.ifEmpty { "image" },
                at = p.taken_at ?: p.created, width = p.width, height = p.height,
                caption = "", blobs = blobs,
            )
        }
        if (refs.isEmpty()) return null
        return shareCrypto.sealManifest(ShareManifests.galleryManifest(album.name, allowDownload, entries), sk) to refs.distinct()
    }

    override suspend fun createAlbumShare(albumId: String, opts: ShareOptions): Outcome<ShareResult> =
        withContext(Dispatchers.IO) {
            val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
            val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
            val manifest = currentGallery() ?: return@withContext Outcome.Err(ErrorKind.NETWORK)

            val sk = shareCrypto.newShareKey()
            val (sealed, refs) = buildAlbumShare(manifest, albumId, sk, vk, opts.allowDownload)
                ?: return@withContext Outcome.Err(ErrorKind.HTTP)
            val token = try {
                val res = apiProvider(session).createGalleryShare(
                    ShareCreateRequest(kind = null, sealed, refs, allowDownload = opts.allowDownload, expiresAt = opts.expiresAtIso, password = opts.password?.trim()?.ifBlank { null }),
                )
                if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.HTTP)
                res.body()?.token ?: return@withContext Outcome.Err(ErrorKind.NETWORK)
            } catch (e: Exception) {
                return@withContext Outcome.Err(ErrorKind.NETWORK, e)
            }

            val info = ShareInfo(
                token = token, sk = sk, allowDownload = opts.allowDownload,
                hasPassword = !opts.password.isNullOrBlank(),
                expiresAt = opts.expiresAtIso, created = nowIso(),
            )
            val saved = galleryRepo.save { m -> applyAlbumShare(m, albumId, info) }
            if (saved is Outcome.Err) return@withContext saved
            Outcome.Ok(ShareResult(token, sk, linkFor(session.baseUrl, token, sk)))
        }

    /** Re-push an existing album share (same token + key) after content/option changes. */
    override suspend fun updateAlbumShare(albumId: String, opts: ShareOptions): Outcome<ShareResult> =
        withContext(Dispatchers.IO) {
            val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
            val vk = vaultKeyHolder.get() ?: return@withContext Outcome.Err(ErrorKind.DECRYPT)
            val manifest = currentGallery() ?: return@withContext Outcome.Err(ErrorKind.NETWORK)
            val existing = manifest.albums.find { it.id == albumId }?.share
                ?: return@withContext Outcome.Err(ErrorKind.HTTP)

            val sk = existing.sk
            val (sealed, refs) = buildAlbumShare(manifest, albumId, sk, vk, opts.allowDownload)
                ?: return@withContext Outcome.Err(ErrorKind.HTTP)
            val newPassword = opts.password?.trim()?.ifBlank { null }
            val clearPassword = if (newPassword == null && !existing.hasPassword) true else null
            try {
                val res = apiProvider(session).updateGalleryShare(
                    existing.token,
                    de.ledgerline.app.data.remote.dto.ShareUpdateRequest(sealed, refs, allowDownload = opts.allowDownload, expiresAt = opts.expiresAtIso, password = newPassword, clearPassword = clearPassword),
                )
                if (!res.isSuccessful) return@withContext Outcome.Err(ErrorKind.HTTP)
            } catch (e: Exception) {
                return@withContext Outcome.Err(ErrorKind.NETWORK, e)
            }

            val info = existing.copy(
                allowDownload = opts.allowDownload, expiresAt = opts.expiresAtIso,
                hasPassword = when { newPassword != null -> true; clearPassword == true -> false; else -> existing.hasPassword },
            )
            val saved = galleryRepo.save { m -> applyAlbumShare(m, albumId, info) }
            if (saved is Outcome.Err) return@withContext saved
            Outcome.Ok(ShareResult(existing.token, sk, linkFor(session.baseUrl, existing.token, sk)))
        }

    override suspend fun revokeAlbumShare(albumId: String): Outcome<Unit> = withContext(Dispatchers.IO) {
        val session = sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        val manifest = currentGallery() ?: return@withContext Outcome.Err(ErrorKind.NETWORK)
        val token = manifest.albums.find { it.id == albumId }?.share?.token
            ?: return@withContext Outcome.Ok(Unit)
        try { apiProvider(session).deleteGalleryShare(token) } catch (_: Exception) { /* best effort */ }
        val saved = galleryRepo.save { m -> applyAlbumShare(m, albumId, null) }
        if (saved is Outcome.Err) saved else Outcome.Ok(Unit)
    }

    private fun applyAlbumShare(m: GalleryManifest, albumId: String, info: ShareInfo?): GalleryManifest =
        m.copy(albums = m.albums.map { if (it.id == albumId) it.copy(share = info) else it })

    // ---- helpers ---------------------------------------------------------------

    private suspend fun currentWorkspace(): WorkspaceManifest? =
        workspaceCache.value.value?.manifest
            ?: (workspaceRepo.load() as? Outcome.Ok)?.value?.manifest

    private suspend fun currentGallery(): GalleryManifest? =
        galleryCache.value.value?.manifest
            ?: (galleryRepo.load() as? Outcome.Ok)?.value?.manifest

    private fun nowIso(): String = java.time.Instant.now().toString()

}
