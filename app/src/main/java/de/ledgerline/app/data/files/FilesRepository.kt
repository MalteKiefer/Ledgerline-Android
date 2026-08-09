package de.ledgerline.app.data.files

import de.ledgerline.app.core.ErrorKind
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.offline.Connectivity
import de.ledgerline.app.data.remote.FilesApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.files.FileEntry
import de.ledgerline.app.domain.model.files.FileFolder
import de.ledgerline.app.domain.model.files.FileLabel
import de.ledgerline.app.domain.model.files.FileVersion
import de.ledgerline.app.domain.model.files.FilesData
import de.ledgerline.app.domain.model.files.FilesStats
import de.ledgerline.app.domain.model.files.FilesTrash
import de.ledgerline.app.domain.model.files.FolderShareView
import de.ledgerline.app.domain.model.files.ShareView
import de.ledgerline.app.domain.model.files.SharedBrowse
import de.ledgerline.app.domain.model.files.SharedWithMe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The files data layer for the plaintext-relational pivot (no client crypto). [data] is the in-memory
 * snapshot the UI observes; [load] pulls `GET /files/data`. Mutations are per-record REST calls; on
 * success the returned row is patched into the snapshot for a snappy UI, and a later [load] reconciles
 * server-computed fields (usage, sha256, version). Online-only — there is no local cache/outbox.
 */
@Singleton
class FilesRepository @Inject constructor(
    private val sessionHolder: SessionHolder,
    private val connectivity: Connectivity,
) {
    private val _data = MutableStateFlow<FilesData?>(null)
    val data: StateFlow<FilesData?> = _data.asStateFlow()

    private fun api(): FilesApi {
        val s = sessionHolder.get() ?: error("no session")
        return NetworkFactory.createFiles(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin)
    }

    fun clear() { _data.value = null }

    /** Pull the full owner-scoped listing. Keeps the last snapshot on transient failure. */
    suspend fun load(): Outcome<FilesData> = withContext(Dispatchers.IO) {
        sessionHolder.get() ?: return@withContext Outcome.Err(ErrorKind.HTTP)
        try {
            val res = api().filesData()
            when {
                res.code() == 401 -> Outcome.Err(ErrorKind.HTTP)
                !res.isSuccessful -> _data.value?.let { Outcome.Ok(it) } ?: Outcome.Err(ErrorKind.NETWORK)
                else -> { val body = res.body()!!; _data.value = body; Outcome.Ok(body) }
            }
        } catch (e: Exception) {
            _data.value?.let { Outcome.Ok(it) } ?: Outcome.Err(ErrorKind.NETWORK, e)
        }
    }

    private fun cur() = _data.value ?: FilesData()
    private fun publish(d: FilesData) { _data.value = d }
    private fun upsertFile(v: FileEntry) = publish(cur().let { it.copy(files = it.files.upsert(v) { x -> x.id == v.id }) })
    private fun removeFile(id: Int) = publish(cur().let { it.copy(files = it.files.filterNot { f -> f.id == id }) })
    private fun upsertFolder(v: FileFolder) = publish(cur().let { it.copy(folders = it.folders.upsert(v) { x -> x.id == v.id }) })
    private fun removeFolder(id: Int) = publish(cur().let {
        it.copy(folders = it.folders.filterNot { f -> f.id == id }, files = it.files.filterNot { f -> f.folderId == id })
    })
    private fun upsertLabel(v: FileLabel) = publish(cur().let { it.copy(labels = it.labels.upsert(v) { x -> x.id == v.id }) })
    private fun removeLabel(id: Int) = publish(cur().let { it.copy(labels = it.labels.filterNot { l -> l.id == id }) })

    // ---- Folders ----
    suspend fun createFolder(name: String, parentId: Int?): Outcome<FileFolder> =
        record({ api().createFolder(buildJsonObject { put("name", name); parentId?.let { put("parent_id", it) } }) }, { it.folder }, ::upsertFolder)

    suspend fun renameFolder(id: Int, name: String): Outcome<FileFolder> =
        record({ api().renameFolder(id, buildJsonObject { put("name", name) }) }, { it.folder }, ::upsertFolder)

    suspend fun moveFolder(id: Int, parentId: Int?): Outcome<FileFolder> =
        record({ api().moveFolder(id, buildJsonObject { put("parent_id", parentId) }) }, { it.folder }, ::upsertFolder)

    suspend fun deleteFolder(id: Int): Outcome<Unit> = delete({ api().deleteFolder(id) }) { removeFolder(id) }

    // ---- File metadata / lifecycle ----
    /** Patch metadata. Send the current [FileEntry.version] as the optimistic guard → 409 CONFLICT. */
    suspend fun updateFile(id: Int, patch: JsonObject): Outcome<FileEntry> {
        val version = cur().files.firstOrNull { it.id == id }?.version
        val body = if (version != null && "version" !in patch) JsonObject(patch + ("version" to kotlinx.serialization.json.JsonPrimitive(version))) else patch
        return record({ api().updateFile(id, body) }, { it.file }, ::upsertFile)
    }

    suspend fun toggleFavorite(id: Int, value: Boolean): Outcome<FileEntry> =
        record({ api().toggle(id, buildJsonObject { put("field", "favorite"); put("value", value) }) }, { it.file }, ::upsertFile)

    suspend fun setFileLabels(id: Int, labelIds: List<Int>): Outcome<FileEntry> =
        record({ api().setFileLabels(id, buildJsonObject { put("label_ids", kotlinx.serialization.json.JsonArray(labelIds.map { kotlinx.serialization.json.JsonPrimitive(it) })) }) }, { it.file }, ::upsertFile)

    suspend fun deleteFile(id: Int): Outcome<Unit> = delete({ api().deleteFile(id) }) { removeFile(id) }
    suspend fun restoreFile(id: Int): Outcome<FileEntry> = record({ api().restoreFile(id) }, { it.file }, ::upsertFile)
    suspend fun forceFile(id: Int): Outcome<Unit> = delete({ api().forceFile(id) }) { }

    // ---- Upload ----
    /** Upload [file]'s bytes: single-shot below [CHUNK_THRESHOLD], S3-style chunked above it. */
    suspend fun upload(file: File, name: String, mime: String?, folderId: Int?): Outcome<FileEntry> {
        if (!connectivity.isOnline()) return Outcome.Err(ErrorKind.NETWORK)
        return if (file.length() >= CHUNK_THRESHOLD) uploadChunked(file, name, mime, folderId)
        else uploadSingle(file, name, mime, folderId)
    }

    private suspend fun uploadSingle(file: File, name: String, mime: String?, folderId: Int?): Outcome<FileEntry> = withContext(Dispatchers.IO) {
        try {
            val body = file.asRequestBody((mime ?: "application/octet-stream").toMediaTypeOrNull())
            val parts = buildList {
                add(MultipartBody.Part.createFormData("file", name, body))
                add(MultipartBody.Part.createFormData("name", name))
                folderId?.let { add(MultipartBody.Part.createFormData("file_folder_id", it.toString())) }
            }
            val res = api().upload(parts)
            fileFrom(res)?.let { upsertFile(it); Outcome.Ok(it) } ?: errFrom(res)
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    private suspend fun uploadChunked(file: File, name: String, mime: String?, folderId: Int?): Outcome<FileEntry> = withContext(Dispatchers.IO) {
        try {
            val init = api().chunkInit(buildJsonObject {
                put("name", name); put("size", file.length()); folderId?.let { put("file_folder_id", it) }
            })
            if (init.code() == 413) return@withContext Outcome.Err(ErrorKind.QUOTA)
            val session = init.body() ?: return@withContext Outcome.Err(ErrorKind.NETWORK)
            val partSize = session.partSize.coerceAtLeast(1)
            file.inputStream().use { stream ->
                val buf = ByteArray(partSize.toInt())
                var index = 0
                while (true) {
                    val read = stream.readNBytesCompat(buf)
                    if (read <= 0) break
                    val slice = if (read == buf.size) buf else buf.copyOf(read)
                    val part = MultipartBody.Part.createFormData("file", name, slice.toRequestBody((mime ?: "application/octet-stream").toMediaTypeOrNull()))
                    val pr = api().chunkPart(session.id.toTextPart(), index.toString().toTextPart(), part)
                    if (pr.code() == 413) { runCatching { api().chunkAbort(buildJsonObject { put("id", session.id) }) }; return@withContext Outcome.Err(ErrorKind.QUOTA) }
                    if (!pr.isSuccessful) { runCatching { api().chunkAbort(buildJsonObject { put("id", session.id) }) }; return@withContext Outcome.Err(ErrorKind.NETWORK) }
                    index++
                }
            }
            val res = api().chunkComplete(buildJsonObject { put("id", session.id) })
            fileFrom(res)?.let { upsertFile(it); Outcome.Ok(it) } ?: errFrom(res)
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    /** Replace a file's content with a new revision (archives the old bytes into version history). */
    suspend fun replaceContent(id: Int, file: File, name: String, mime: String?): Outcome<FileEntry> = withContext(Dispatchers.IO) {
        if (!connectivity.isOnline()) return@withContext Outcome.Err(ErrorKind.NETWORK)
        try {
            val part = MultipartBody.Part.createFormData("file", name, file.asRequestBody((mime ?: "application/octet-stream").toMediaTypeOrNull()))
            val res = api().replaceContent(id, part)
            fileFrom(res)?.let { upsertFile(it); Outcome.Ok(it) } ?: errFrom(res)
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }

    // ---- Download (binary) ----
    /** Stream a file's plaintext bytes into [dest]. Returns true on success. */
    suspend fun downloadToFile(id: Int, dest: File, versionId: Int? = null): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val res = if (versionId != null) api().versionRaw(id, versionId) else api().raw(id)
            res.takeIf { it.isSuccessful }?.body()?.let { body -> dest.outputStream().use { copyBody(body, it) }; true } ?: false
        }.getOrDefault(false)
    }

    suspend fun thumbBytes(id: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { api().thumb(id).takeIf { it.isSuccessful }?.body()?.bytes() }.getOrNull()
    }

    // ---- Versions ----
    suspend fun versions(id: Int): List<FileVersion> = get { api().versions(id) }?.versions.orEmpty()
    suspend fun restoreVersion(id: Int, versionId: Int): Outcome<FileEntry> = record({ api().restoreVersion(id, versionId) }, { it.file }, ::upsertFile)

    // ---- Labels ----
    suspend fun createLabel(name: String, color: String): Outcome<FileLabel> =
        record({ api().createLabel(buildJsonObject { put("name", name); put("color", color) }) }, { it.label }, ::upsertLabel)
    suspend fun updateLabel(id: Int, name: String, color: String): Outcome<FileLabel> =
        record({ api().updateLabel(id, buildJsonObject { put("name", name); put("color", color) }) }, { it.label }, ::upsertLabel)
    suspend fun deleteLabel(id: Int): Outcome<Unit> = delete({ api().deleteLabel(id) }) { removeLabel(id) }

    /** Standalone folder list (`GET /files/folders`) — same rows as in [load]'s snapshot. */
    suspend fun folders(): List<FileFolder> = get { api().folders() }?.folders.orEmpty()
    /** Standalone label list (`GET /files/labels`) — same rows as in [load]'s snapshot. */
    suspend fun labels(): List<FileLabel> = get { api().labels() }?.labels.orEmpty()

    // ---- Trash / stats / search ----
    suspend fun trash(): FilesTrash? = get { api().trash() }
    suspend fun emptyTrash(): Int? = get { api().emptyTrash() }?.deleted
    suspend fun stats(): FilesStats? = get { api().stats() }
    suspend fun search(q: String): List<FileEntry> {
        if (q.isBlank()) return emptyList()
        return get { api().search(q) }?.files.orEmpty()
    }

    /** Zip [ids] (or a whole [folderId]) server-side and stream the archive into [dest]. */
    suspend fun zipToFile(dest: File, ids: List<Int>? = null, folderId: Int? = null): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            ids?.let { put("ids", kotlinx.serialization.json.JsonArray(it.map { id -> kotlinx.serialization.json.JsonPrimitive(id) })) }
            folderId?.let { put("folder_id", it) }
        }
        runCatching { api().zip(body).takeIf { it.isSuccessful }?.body()?.let { b -> dest.outputStream().use { copyBody(b, it) }; true } ?: false }.getOrDefault(false)
    }

    // ---- Sharing: public links (owner side) ----
    suspend fun createShare(body: JsonObject): ShareView? = get { api().createShare(body) }?.share
    suspend fun updateShare(id: Int, body: JsonObject): ShareView? = get { api().updateShare(id, body) }?.share
    suspend fun deleteShare(id: Int): Boolean = ok { api().deleteShare(id) }

    // ---- Sharing: cross-user folder shares ----
    suspend fun folderShares(): List<FolderShareView> = get { api().folderShares() }?.shares.orEmpty()
    suspend fun createFolderShare(folderId: Int, email: String, role: String): FolderShareView? =
        get { api().createFolderShare(buildJsonObject { put("file_folder_id", folderId); put("email", email); put("role", role) }) }?.share
    suspend fun updateFolderShareMember(shareId: Int, userId: Int, role: String): FolderShareView? =
        get { api().updateFolderShareMember(shareId, buildJsonObject { put("user_id", userId); put("role", role) }) }?.share
    suspend fun removeFolderShareMember(shareId: Int, userId: Int): Boolean =
        ok { api().removeFolderShareMember(shareId, buildJsonObject { put("user_id", userId) }) }
    suspend fun deleteFolderShare(shareId: Int): Boolean = ok { api().deleteFolderShare(shareId) }

    // ---- Sharing: shared-with-me (member side) ----
    suspend fun sharedWithMe(): List<SharedWithMe> = get { api().sharedWithMe() }?.shares.orEmpty()
    suspend fun browseShared(shareId: Int): SharedBrowse? = get { api().browseShared(shareId) }
    suspend fun downloadSharedToFile(shareId: Int, fileId: Int, dest: File): Boolean = withContext(Dispatchers.IO) {
        runCatching { api().sharedRaw(shareId, fileId).takeIf { it.isSuccessful }?.body()?.let { b -> dest.outputStream().use { copyBody(b, it) }; true } ?: false }.getOrDefault(false)
    }
    suspend fun uploadShared(shareId: Int, file: File, name: String, mime: String?, folderId: Int?): Outcome<FileEntry> = withContext(Dispatchers.IO) {
        if (!connectivity.isOnline()) return@withContext Outcome.Err(ErrorKind.NETWORK)
        try {
            val parts = buildList {
                add(MultipartBody.Part.createFormData("file", name, file.asRequestBody((mime ?: "application/octet-stream").toMediaTypeOrNull())))
                add(MultipartBody.Part.createFormData("name", name))
                folderId?.let { add(MultipartBody.Part.createFormData("file_folder_id", it.toString())) }
            }
            val res = api().sharedUpload(shareId, parts)
            fileFrom(res)?.let { Outcome.Ok(it) } ?: errFrom(res)
        } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
    }
    suspend fun renameShared(shareId: Int, fileId: Int, name: String): Boolean =
        get { api().sharedRename(shareId, fileId, buildJsonObject { put("name", name) }) } != null
    suspend fun deleteShared(shareId: Int, fileId: Int): Boolean = ok { api().sharedDelete(shareId, fileId) }

    // ---- generic helpers ----
    private suspend fun <W, R> record(call: suspend () -> Response<W>, extract: (W) -> R?, upsert: (R) -> Unit): Outcome<R> {
        if (!connectivity.isOnline()) return Outcome.Err(ErrorKind.NETWORK)
        return withContext(Dispatchers.IO) {
            try {
                val res = call()
                if (!res.isSuccessful) return@withContext Outcome.Err(mapCode(res.code()))
                val r = res.body()?.let(extract) ?: return@withContext Outcome.Err(ErrorKind.NETWORK)
                upsert(r); Outcome.Ok(r)
            } catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
        }
    }

    private suspend fun delete(call: suspend () -> Response<*>, onOk: () -> Unit): Outcome<Unit> {
        if (!connectivity.isOnline()) return Outcome.Err(ErrorKind.NETWORK)
        return withContext(Dispatchers.IO) {
            try { if (call().isSuccessful) { onOk(); Outcome.Ok(Unit) } else Outcome.Err(ErrorKind.NETWORK) }
            catch (e: Exception) { Outcome.Err(ErrorKind.NETWORK, e) }
        }
    }

    private suspend fun <T> get(call: suspend () -> Response<T>): T? = withContext(Dispatchers.IO) {
        runCatching { call().takeIf { it.isSuccessful }?.body() }.getOrNull()
    }
    private suspend fun ok(call: suspend () -> Response<*>): Boolean = withContext(Dispatchers.IO) {
        runCatching { call().isSuccessful }.getOrDefault(false)
    }

    private fun fileFrom(res: Response<de.ledgerline.app.data.remote.FileResponse>): FileEntry? =
        res.takeIf { it.isSuccessful }?.body()?.file
    private fun errFrom(res: Response<*>): Outcome<Nothing> = Outcome.Err(mapCode(res.code()))
    private fun mapCode(code: Int): ErrorKind = when (code) {
        409 -> ErrorKind.CONFLICT
        413 -> ErrorKind.QUOTA
        429 -> ErrorKind.RATE_LIMITED
        else -> ErrorKind.NETWORK
    }

    private fun copyBody(body: ResponseBody, out: OutputStream) { body.byteStream().use { it.copyTo(out) } }
    private fun String.toTextPart(): okhttp3.RequestBody = toRequestBody("text/plain".toMediaTypeOrNull())

    companion object { private const val CHUNK_THRESHOLD = 32L * 1024 * 1024 }
}

/** Replace the element matching [where] with [v], or append it when none matches. */
private inline fun <T> List<T>.upsert(v: T, where: (T) -> Boolean): List<T> {
    val i = indexOfFirst(where)
    return if (i >= 0) toMutableList().also { it[i] = v } else this + v
}

/** Read as many bytes as possible into [buf] (loops over partial reads); returns total read (0 at EOF). */
private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
    var off = 0
    while (off < buf.size) {
        val n = read(buf, off, buf.size - off)
        if (n < 0) break
        off += n
    }
    return off
}
