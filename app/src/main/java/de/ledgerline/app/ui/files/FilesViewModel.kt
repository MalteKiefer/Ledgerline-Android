package de.ledgerline.app.ui.files

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.data.files.FilesRepository
import de.ledgerline.app.domain.model.files.FileEntry
import de.ledgerline.app.domain.model.files.FileFolder
import de.ledgerline.app.domain.model.files.FilesData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale
import javax.inject.Inject

/**
 * Shell-scoped state for the Files section: the [FilesRepository] snapshot plus client-side folder
 * navigation (the server returns a flat folder list; the tree + breadcrumb are reconstructed here).
 * Mutations are thin wrappers over the repository with a completion callback. Online-only.
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: FilesRepository,
    private val sessionHolder: de.ledgerline.app.core.SessionHolder,
) : ViewModel() {

    /** Base URL of the paired server (for building public share links). */
    fun baseUrl(): String? = sessionHolder.get()?.baseUrl
    val data: StateFlow<FilesData?> = repo.data

    /** Folder navigation stack (root = empty). The last id is the folder currently shown. */
    private val _stack = MutableStateFlow<List<FileFolder>>(emptyList())
    val stack: StateFlow<List<FileFolder>> = _stack.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            repo.load()
            _loading.value = false
            _refreshing.value = false
            reconcileStack()
        }
    }

    fun pullRefresh() { _refreshing.value = true; refresh() }

    /** The id of the folder currently open (null = root). */
    val currentFolderId: Int? get() = _stack.value.lastOrNull()?.id

    fun openFolder(folder: FileFolder) { _stack.value = _stack.value + folder }
    /** Pop to root, or one level up. Returns false when already at root (let the shell handle back). */
    fun goUp(): Boolean {
        if (_stack.value.isEmpty()) return false
        _stack.value = _stack.value.dropLast(1)
        return true
    }
    fun goToRoot() { _stack.value = emptyList() }
    fun goTo(folder: FileFolder) {
        val idx = _stack.value.indexOfFirst { it.id == folder.id }
        if (idx >= 0) _stack.value = _stack.value.take(idx + 1)
    }

    /** After a reload, drop any stack folders that no longer exist (deleted/trashed elsewhere). */
    private fun reconcileStack() {
        val live = data.value?.folders?.associateBy { it.id } ?: return
        val trimmed = _stack.value.takeWhile { live[it.id]?.deletedAt == null }
            .map { live[it.id] ?: it }
        if (trimmed != _stack.value) _stack.value = trimmed
    }

    // ---- Derived listing for the current folder (live rows only) ----
    fun childFolders(data: FilesData?, parentId: Int?): List<FileFolder> =
        data?.folders.orEmpty().filter { it.deletedAt == null && it.parentId == parentId }.sortedBy { it.name.lowercase() }

    fun filesIn(data: FilesData?, folderId: Int?): List<FileEntry> =
        data?.files.orEmpty().filter { it.deletedAt == null && it.folderId == folderId }.sortedByDescending { it.updatedAt ?: "" }

    // ---- Mutations ----
    private fun <T> run(block: suspend () -> Outcome<T>, done: (Boolean) -> Unit) {
        viewModelScope.launch { done(block() is Outcome.Ok) }
    }

    fun createFolder(name: String, done: (Boolean) -> Unit) = run({ repo.createFolder(name, currentFolderId) }, done)
    fun renameFolder(id: Int, name: String, done: (Boolean) -> Unit) = run({ repo.renameFolder(id, name) }, done)
    fun moveFolder(id: Int, parentId: Int?, done: (Boolean) -> Unit) = run({ repo.moveFolder(id, parentId) }, done)
    fun deleteFolder(id: Int, done: (Boolean) -> Unit) = run({ repo.deleteFolder(id) }, { ok -> reconcileStack(); done(ok) })

    fun renameFile(id: Int, name: String, done: (Boolean) -> Unit) =
        run({ repo.updateFile(id, buildJsonObject { put("name", name) }) }, done)
    /** Patch a file's free-text tags + note. */
    fun updateTagsNote(id: Int, tags: List<String>, note: String, done: (Boolean) -> Unit) =
        run({
            repo.updateFile(id, buildJsonObject {
                put("tags", kotlinx.serialization.json.JsonArray(tags.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("note", note)
            })
        }, done)
    fun moveFile(id: Int, folderId: Int?, done: (Boolean) -> Unit) =
        run({ repo.updateFile(id, buildJsonObject { put("file_folder_id", folderId) }) }, done)
    fun toggleFavorite(id: Int, value: Boolean, done: (Boolean) -> Unit) = run({ repo.toggleFavorite(id, value) }, done)
    fun deleteFile(id: Int, done: (Boolean) -> Unit) = run({ repo.deleteFile(id) }, done)

    fun upload(file: File, name: String, mime: String?, done: (Boolean) -> Unit) =
        run({ repo.upload(file, name, mime, currentFolderId) }, done)

    /** Download a file's bytes to a private cache file for external viewing. Null on failure. */
    suspend fun downloadToCache(entry: FileEntry): File? {
        val dir = File(context.cacheDir, "docs").apply { mkdirs() }
        val safe = entry.name.ifBlank { "file_${entry.id}" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(dir, "${entry.id}_$safe")
        return if (repo.downloadToFile(entry.id, dest)) dest else null
    }

    /** Stream a file's bytes into [dest] (a user-picked SAF destination copies from cache). */
    suspend fun downloadFor(entry: FileEntry, dest: File): Boolean = repo.downloadToFile(entry.id, dest)

    suspend fun search(q: String): List<FileEntry> = repo.search(q)
    suspend fun thumb(id: Int): ByteArray? = repo.thumbBytes(id)

    // In-memory thumbnail cache (id+version → decoded bitmap or null when unavailable).
    private val thumbCache = mutableMapOf<String, androidx.compose.ui.graphics.ImageBitmap?>()
    /** Decoded square thumbnail for an image file, or null (non-image / failed). Cached by id+version. */
    suspend fun thumbnail(entry: FileEntry): androidx.compose.ui.graphics.ImageBitmap? {
        val key = "${entry.id}:${entry.version}"
        thumbCache[key]?.let { return it }
        if (thumbCache.containsKey(key)) return null
        val mime = entry.mime?.lowercase().orEmpty()
        if (!mime.startsWith("image/")) { thumbCache[key] = null; return null }
        val bytes = repo.thumbBytes(entry.id)
        val bmp = bytes?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.let { b -> androidx.compose.ui.graphics.asImageBitmap(b) } }.getOrNull() }
        thumbCache[key] = bmp
        return bmp
    }

    /** Zip a whole folder subtree server-side into [dest]. */
    suspend fun zipFolder(folderId: Int, dest: File): Boolean = repo.zipToFile(dest, folderId = folderId)

    /** Replace a file's content with new bytes (adds a version). */
    fun replaceContent(id: Int, file: File, name: String, mime: String?, done: (Boolean) -> Unit) =
        run({ repo.replaceContent(id, file, name, mime) }, done)

    fun setFavorite(id: Int, value: Boolean, done: (Boolean) -> Unit) = run({ repo.toggleFavorite(id, value) }, done)

    // ---- Versions ----
    suspend fun versions(id: Int): List<de.ledgerline.app.domain.model.files.FileVersion> = repo.versions(id)
    fun restoreVersion(id: Int, versionId: Int, done: (Boolean) -> Unit) = run({ repo.restoreVersion(id, versionId) }, done)
    suspend fun downloadVersionToCache(entry: FileEntry, versionId: Int): File? {
        val dir = File(context.cacheDir, "docs").apply { mkdirs() }
        val dest = File(dir, "v${versionId}_${entry.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        return if (repo.downloadToFile(entry.id, dest, versionId)) dest else null
    }

    // ---- Labels ----
    fun createLabel(name: String, color: String, done: (Boolean) -> Unit) = run({ repo.createLabel(name, color) }, done)
    fun updateLabel(id: Int, name: String, color: String, done: (Boolean) -> Unit) = run({ repo.updateLabel(id, name, color) }, done)
    fun deleteLabel(id: Int, done: (Boolean) -> Unit) = run({ repo.deleteLabel(id) }, done)
    fun setLabels(fileId: Int, labelIds: List<Int>, done: (Boolean) -> Unit) = run({ repo.setFileLabels(fileId, labelIds) }, done)

    // ---- Trash ----
    suspend fun loadTrash(): de.ledgerline.app.domain.model.files.FilesTrash? = repo.trash()
    fun restoreFile(id: Int, done: (Boolean) -> Unit) = run({ repo.restoreFile(id) }, done)
    fun forceFile(id: Int, done: (Boolean) -> Unit) = run({ repo.forceFile(id) }, done)
    fun emptyTrash(done: (Boolean) -> Unit) = viewModelScope.launch { val n = repo.emptyTrash(); done(n != null) }

    // ---- Stats ----
    suspend fun stats(): de.ledgerline.app.domain.model.files.FilesStats? = repo.stats()

    /** Copy a downloaded file's bytes into a user-picked SAF destination (returns true on success). */
    suspend fun saveTo(entry: FileEntry, out: java.io.OutputStream): Boolean = withContext(Dispatchers.IO) {
        val tmp = downloadToCache(entry) ?: return@withContext false
        runCatching { tmp.inputStream().use { it.copyTo(out) }; true }.getOrDefault(false)
    }

    // ---- Sharing: public links ----
    /** Public link URL for a share token: {baseUrl}/file-share/{token}. */
    fun shareUrl(token: String): String = (baseUrl()?.trimEnd('/') ?: "") + "/file-share/" + token

    suspend fun createFileShare(fileId: Int, password: String?, allowDownload: Boolean, expiresAt: String?) =
        repo.createShare(kotlinx.serialization.json.buildJsonObject {
            put("kind", "file"); put("file_id", fileId); put("allow_download", allowDownload)
            if (!password.isNullOrBlank()) put("password", password)
            if (!expiresAt.isNullOrBlank()) put("expires_at", expiresAt)
        })
    suspend fun createFolderShare(folderId: Int, password: String?, allowDownload: Boolean, expiresAt: String?) =
        repo.createShare(kotlinx.serialization.json.buildJsonObject {
            put("kind", "folder"); put("file_folder_id", folderId); put("allow_download", allowDownload)
            if (!password.isNullOrBlank()) put("password", password)
            if (!expiresAt.isNullOrBlank()) put("expires_at", expiresAt)
        })
    fun deleteShare(id: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.deleteShare(id)) }

    // ---- Sharing: cross-user folder shares ----
    suspend fun folderShares() = repo.folderShares()
    suspend fun createUserFolderShare(folderId: Int, email: String, role: String) = repo.createFolderShare(folderId, email, role)
    fun updateFolderShareMember(shareId: Int, userId: Int, role: String, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(repo.updateFolderShareMember(shareId, userId, role) != null) }
    fun removeFolderShareMember(shareId: Int, userId: Int, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(repo.removeFolderShareMember(shareId, userId)) }
    fun deleteFolderShare(shareId: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.deleteFolderShare(shareId)) }

    // ---- Sharing: shared-with-me ----
    suspend fun sharedWithMe() = repo.sharedWithMe()
    suspend fun browseShared(shareId: Int) = repo.browseShared(shareId)
    suspend fun downloadSharedToCache(shareId: Int, file: de.ledgerline.app.domain.model.files.SharedFile): File? {
        val dir = File(context.cacheDir, "docs").apply { mkdirs() }
        val dest = File(dir, "s${shareId}_${file.id}_${file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        return if (repo.downloadSharedToFile(shareId, file.id, dest)) dest else null
    }
    fun renameShared(shareId: Int, fileId: Int, name: String, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(repo.renameShared(shareId, fileId, name)) }
    fun deleteShared(shareId: Int, fileId: Int, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(repo.deleteShared(shareId, fileId)) }
    fun uploadShared(shareId: Int, file: File, name: String, mime: String?, folderId: Int?, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(repo.uploadShared(shareId, file, name, mime, folderId) is Outcome.Ok) }

    fun clear() = repo.clear()
}

/** Human-readable byte size (1 KB = 1024). */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}
