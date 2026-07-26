package de.ledgerline.app.ui.workspace.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.security.LockGuard
import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.domain.model.WorkspaceManifest
import de.ledgerline.app.domain.usecase.FileBlobs
import de.ledgerline.app.domain.usecase.FilesUsage
import de.ledgerline.app.domain.usecase.ImportFile
import de.ledgerline.app.domain.model.FileVersion
import de.ledgerline.app.domain.usecase.LoadWorkspace
import de.ledgerline.app.domain.usecase.MutateWorkspace
import de.ledgerline.app.domain.workspace.FileOps
import de.ledgerline.app.domain.workspace.WorkspaceSearch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import javax.inject.Inject

data class FilesUi(
    val loading: Boolean = false,
    val error: Boolean = false,
    val folders: List<NamedFolder> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val canGoBack: Boolean = false,
)

/** File list sort orders. */
enum class FileSort { NAME_ASC, NAME_DESC, DATE_DESC, DATE_ASC, SIZE_DESC, SIZE_ASC }

private fun List<FileEntry>.sortedBy(order: FileSort): List<FileEntry> = when (order) {
    FileSort.NAME_ASC -> sortedBy { it.name.lowercase() }
    FileSort.NAME_DESC -> sortedByDescending { it.name.lowercase() }
    FileSort.DATE_DESC -> sortedByDescending { it.created ?: "" }
    FileSort.DATE_ASC -> sortedBy { it.created ?: "" }
    FileSort.SIZE_DESC -> sortedByDescending { it.size }
    FileSort.SIZE_ASC -> sortedBy { it.size }
}

/** State of the in-app file viewer (in-memory plaintext bytes, never persisted). */
sealed interface ViewerState {
    data object Idle : ViewerState
    data object Loading : ViewerState
    data class Ready(val file: FileEntry, val bytes: ByteArray) : ViewerState {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ready) return false
            return file == other.file && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * file.hashCode() + bytes.contentHashCode()
    }
    data class Failed(val msg: String) : ViewerState
}

/** Files blob storage usage (used/quota bytes); null until loaded. */
data class UsageInfo(val used: Long, val quota: Long)

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
    private val mutate: MutateWorkspace,
    private val blobRepo: FileBlobs,
    private val filesUsage: FilesUsage,
    private val lockGuard: LockGuard,
    private val importFile: ImportFile,
    degradedState: de.ledgerline.app.core.offline.DegradedState,
) : ViewModel() {

    /** True when the files store is degraded (a shard blob is missing); writes are frozen. */
    val degraded: StateFlow<Boolean> = degradedState.files
    private val stack = ArrayDeque<String?>().apply { addLast(null) }   // current folder = last
    private val _state = MutableStateFlow(FilesUi(loading = true))
    val state: StateFlow<FilesUi> = _state

    /** File list sort order. */
    private val _sort = MutableStateFlow(FileSort.NAME_ASC)
    val sort: StateFlow<FileSort> = _sort
    fun setSort(s: FileSort) { _sort.value = s; recompute() }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** When true, the list shows only trashed files (the trash view). */
    private val _showTrash = MutableStateFlow(false)
    val showTrash: StateFlow<Boolean> = _showTrash

    /** Number of trashed files across all folders (drives the "Trash (N)" affordance). */
    private val _trashCount = MutableStateFlow(0)
    val trashCount: StateFlow<Int> = _trashCount

    private val _usage = MutableStateFlow<UsageInfo?>(null)
    val usage: StateFlow<UsageInfo?> = _usage

    private val _viewer = MutableStateFlow<ViewerState>(ViewerState.Idle)
    val viewer: StateFlow<ViewerState> = _viewer

    /** True while an in-app editor save (re-encrypt + manifest write) is in flight. */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    /** Transient one-shot user message (success/failure); cleared once shown. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Live text-search query; filters the current folder view (files + folders by name). */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    init {
        viewModelScope.launch {
            cache.value.collect { ws ->
                if (ws != null) recompute() else _state.value = FilesUi(loading = true)
            }
        }
        loadUsage()
    }

    /** Fetch files blob usage (used/quota) and publish it; silently ignores failure. */
    fun loadUsage() = viewModelScope.launch {
        filesUsage.invoke()?.let { (used, quota) -> _usage.value = UsageInfo(used, quota) }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        if (load.invoke() is Outcome.Err) {
            _state.value = _state.value.copy(loading = false, error = true)
        }
        loadUsage()
    }

    /**
     * Suppress exactly one auto-lock before launching a SAF picker (upload/export),
     * which briefly backgrounds the app and would otherwise wipe the Vault Key.
     */
    fun armLockSuppression() = lockGuard.armSkipOnce()

    fun open(folderId: String) { stack.addLast(folderId); recompute() }
    fun back() { if (stack.size > 1) { stack.removeLast(); recompute() } }

    fun fileById(id: String): FileEntry? = cache.value.value?.manifest?.files?.firstOrNull { it.id == id }

    // ---- Manifest mutations (the cache-flow collector recomputes the list automatically) ----

    fun createFolder(name: String) = viewModelScope.launch {
        val parent = stack.last()
        mutate.invoke { m -> m.copy(fileFolders = m.fileFolders + NamedFolder(newId(), name, parent)) }
    }

    fun renameFolder(id: String, name: String) = viewModelScope.launch {
        mutate.invoke { m ->
            m.copy(fileFolders = m.fileFolders.map { if (it.id == id) it.copy(name = name) else it })
        }
    }

    fun renameFile(id: String, name: String) = viewModelScope.launch {
        mutate.invoke { m ->
            m.copy(files = m.files.map { if (it.id == id) it.copy(name = name) else it })
        }
    }

    /** The folders available as move targets (whole tree). */
    fun allFolders(): List<NamedFolder> = cache.value.value?.manifest?.fileFolders.orEmpty()

    fun moveFile(id: String, folderId: String?) = viewModelScope.launch { mutate.invoke { FileOps.moveFile(it, id, folderId) } }
    fun toggleFavorite(id: String) = viewModelScope.launch { mutate.invoke { FileOps.toggleFavorite(it, id) } }
    fun setTags(id: String, tags: List<String>) = viewModelScope.launch { mutate.invoke { FileOps.setTags(it, id, tags) } }

    /** Restore a saved version as the file's current content (the outgoing blob becomes a version). */
    fun restoreVersion(id: String, version: de.ledgerline.app.domain.model.FileVersion) = viewModelScope.launch {
        val now = java.time.Instant.now().toString()
        mutate.invoke { FileOps.restoreVersion(it, id, version, now) }
    }

    /** Soft-delete: move the file to the trash. No blobs are freed here — that only
     * happens on permanent delete (see [deleteForever] / [emptyTrash]). */
    fun deleteFile(file: FileEntry) = viewModelScope.launch {
        mutate.invoke { FileOps.trashFile(it, file.id) }
    }

    // ---- Trash view ----

    fun setTrash(show: Boolean) {
        _showTrash.value = show
        recompute()
    }

    fun toggleTrash() = setTrash(!_showTrash.value)

    fun setQuery(q: String) {
        _query.value = q
        recompute()
    }

    /** Move a trashed file back to the active list. */
    fun restore(id: String) = viewModelScope.launch {
        mutate.invoke { FileOps.restoreFile(it, id) }
    }

    /** Permanently delete a trashed file: drop the manifest entry, then free its content
     * blob and every version blob to reclaim quota. */
    fun deleteForever(file: FileEntry) = viewModelScope.launch {
        val res = mutate.invoke { FileOps.removeFile(it, file.id) }
        if (res is Outcome.Ok) {
            blobRepo.deleteBlobs(listOf(file.blob) + file.versions.map { it.blob })
            reconcileLivingSet()
            loadUsage()
        }
    }

    /** Empty the trash: drop every trashed file and free all their blobs (incl. versions). */
    fun emptyTrash() = viewModelScope.launch {
        val trashed = cache.value.value?.manifest?.files?.filter { it.trashed }.orEmpty()
        val freedBlobs = trashed.flatMap { listOf(it.blob) + it.versions.map { v -> v.blob } }
        val res = mutate.invoke { FileOps.emptyTrashFiles(it) }
        if (res is Outcome.Ok) {
            blobRepo.deleteBlobs(freedBlobs)
            reconcileLivingSet()
            loadUsage()
        }
    }

    /**
     * Best-effort living-set reconcile: hand the server every blob still referenced by the current
     * manifest so a blob orphaned by a failed eager DELETE is reclaimed after the 24 h grace. Must
     * run only after a successful manifest save (the cache reflects the post-delete state).
     */
    private suspend fun reconcileLivingSet() {
        val living = cache.value.value?.manifest?.files
            ?.flatMap { listOf(it.blob) + it.versions.map { v -> v.blob } }
            ?.filter { it.isNotBlank() } ?: return
        blobRepo.reconcile(living)
    }

    fun deleteFolder(folderId: String) = viewModelScope.launch {
        val current = cache.value.value?.manifest ?: return@launch
        val subFolders = collectSubtreeFolderIds(current, folderId)
        val freedBlobs = current.files.filter { it.folder in subFolders }.map { it.blob }
        val res = mutate.invoke { m ->
            m.copy(
                files = m.files.filterNot { it.folder in subFolders },
                fileFolders = m.fileFolders.filterNot { it.id in subFolders },
            )
        }
        if (res is Outcome.Ok) {
            blobRepo.deleteBlobs(freedBlobs)
            reconcileLivingSet()
            loadUsage()
        }
    }

    fun uploadPicked(name: String, mime: String, size: Long, open: () -> InputStream) = viewModelScope.launch {
        _busy.value = true
        try {
            if (importFile.invoke(name, mime, size, folder = stack.last(), open = open) is Outcome.Err) {
                _message.value = "Upload failed"
            }
        } finally {
            _busy.value = false
        }
        loadUsage()
    }

    // ---- Viewer (download-to-memory) ----

    fun openFile(file: FileEntry) = viewModelScope.launch {
        _viewer.value = ViewerState.Loading
        _viewer.value = when (val res = blobRepo.downloadToBytes(file.blob, file.encFileKey)) {
            is Outcome.Ok -> ViewerState.Ready(file, res.value)
            is Outcome.Err -> ViewerState.Failed("Could not open file")
        }
    }

    fun closeViewer() { _viewer.value = ViewerState.Idle }

    /**
     * Save edited text back into the vault (zero-knowledge, mirrors the web `saveText`):
     * re-encrypt [content] with a FRESH per-file key as a new blob, snapshot the OLD blob
     * as the newest [FileVersion] (capped, overflow blobs freed), then persist the mutated
     * manifest. The old blob is NOT deleted — it lives on as a version. On success the
     * viewer is re-seeded with the saved bytes so the editor reflects the persisted state.
     */
    fun saveText(file: FileEntry, content: String) = viewModelScope.launch {
        _saving.value = true
        try {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val mime = file.mime.ifBlank { "text/plain" }
            val uploaded = when (
                val r = blobRepo.upload(file.name, mime, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
            ) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> { _message.value = SAVE_FAILED; return@launch }
            }

            val now = Instant.now().toString()
            val snapshot = FileVersion(
                id = newId(), blob = file.blob, encFileKey = file.encFileKey,
                size = file.size, mime = file.mime, name = file.name, created = now,
            )
            // Only snapshot when the blob actually changed (defensive; upload always mints a new id).
            val update = if (uploaded.id != file.blob) {
                FileOps.prependVersion(file.versions, snapshot)
            } else {
                FileOps.VersionUpdate(file.versions, emptyList())
            }

            val updated = file.copy(
                blob = uploaded.id,
                encFileKey = uploaded.encFileKey,
                size = uploaded.size,
                mime = mime,
                versions = update.versions,
            )

            val res = mutate.invoke { m ->
                m.copy(files = m.files.map { if (it.id == file.id) updated else it })
            }
            when (res) {
                is Outcome.Ok -> {
                    if (update.freedBlobs.isNotEmpty()) blobRepo.deleteBlobs(update.freedBlobs)
                    _viewer.value = ViewerState.Ready(updated, bytes)
                    _message.value = SAVED
                    loadUsage()
                }
                is Outcome.Err -> {
                    // Manifest write failed: reclaim the orphan blob we just uploaded.
                    blobRepo.deleteBlobs(listOf(uploaded.id))
                    _message.value = SAVE_FAILED
                }
            }
        } finally {
            _saving.value = false
        }
    }

    // ---- Export (streamed to a SAF sink) ----

    /**
     * Stream-decrypt [file] and hand each plaintext chunk to [write]. The caller owns
     * the sink and closes it; [busy] wraps the transfer and [message] reports the result.
     */
    fun exportTo(write: (ByteArray) -> Unit, file: FileEntry) = viewModelScope.launch {
        _busy.value = true
        try {
            _message.value = when (blobRepo.downloadTo(file.blob, file.encFileKey) { chunk -> write(chunk) }) {
                is Outcome.Ok -> "Saved"
                is Outcome.Err -> "Save failed"
            }
        } finally {
            _busy.value = false
        }
    }

    /**
     * Stream-decrypt [file] straight into [os]. The **caller** owns [os] and must close it
     * (e.g. via `use {}`) so the SAF sink is flushed and closed exactly once after all
     * chunks are written. Returns true on success. [busy] wraps the transfer.
     */
    suspend fun exportToStream(os: OutputStream, file: FileEntry): Boolean {
        _busy.value = true
        return try {
            when (blobRepo.downloadTo(file.blob, file.encFileKey) { chunk -> os.write(chunk) }) {
                is Outcome.Ok -> { os.flush(); true }
                is Outcome.Err -> false
            }
        } catch (_: Exception) {
            false
        } finally {
            _busy.value = false
        }
    }

    fun clearMessage() { _message.value = null }

    private fun collectSubtreeFolderIds(m: WorkspaceManifest, root: String): Set<String> {
        val out = mutableSetOf(root)
        var changed = true
        while (changed) {
            changed = false
            for (f in m.fileFolders) if (f.parent in out && out.add(f.id)) changed = true
        }
        return out
    }

    private fun newId(): String = de.ledgerline.app.core.Ids.newId()

    companion object {
        /** Sentinel messages the UI maps to localized strings (see FilesScreen). */
        const val SAVED = "msg.saved"
        const val SAVE_FAILED = "msg.save_failed"
    }

    private fun recompute() {
        val m = cache.value.value?.manifest
        val allFiles = m?.files.orEmpty()
        _trashCount.value = allFiles.count { it.trashed }
        if (_showTrash.value) {
            // Trash view: ALL trashed files across every folder, by name. No folders / no "..".
            val files = allFiles.filter { it.trashed }.sortedBy { it.name.lowercase() }
            _state.value = FilesUi(false, false, emptyList(), files, canGoBack = false)
            return
        }
        val cwd = stack.last()
        val q = _query.value
        val folders = m?.fileFolders
            ?.filter { it.parent == cwd && WorkspaceSearch.matches(it, q) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        val files = allFiles
            .filter { !it.trashed && it.folder == cwd && WorkspaceSearch.matches(it, q) }
            .sortedBy(_sort.value)
        _state.value = FilesUi(false, false, folders, files, canGoBack = stack.size > 1)
    }
}
