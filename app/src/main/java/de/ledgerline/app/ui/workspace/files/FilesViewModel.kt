package de.ledgerline.app.ui.workspace.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.domain.model.WorkspaceManifest
import de.ledgerline.app.domain.usecase.FileBlobs
import de.ledgerline.app.domain.usecase.LoadWorkspace
import de.ledgerline.app.domain.usecase.MutateWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject

data class FilesUi(
    val loading: Boolean = false,
    val error: Boolean = false,
    val folders: List<NamedFolder> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val canGoBack: Boolean = false,
)

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

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
    private val mutate: MutateWorkspace,
    private val blobRepo: FileBlobs,
) : ViewModel() {
    private val stack = ArrayDeque<String?>().apply { addLast(null) }   // current folder = last
    private val _state = MutableStateFlow(FilesUi(loading = true))
    val state: StateFlow<FilesUi> = _state

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _viewer = MutableStateFlow<ViewerState>(ViewerState.Idle)
    val viewer: StateFlow<ViewerState> = _viewer

    /** Transient one-shot user message (success/failure); cleared once shown. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        viewModelScope.launch {
            cache.value.collect { ws ->
                if (ws != null) recompute() else _state.value = FilesUi(loading = true)
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        if (load.invoke() is Outcome.Err) {
            _state.value = _state.value.copy(loading = false, error = true)
        }
    }

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

    fun deleteFile(file: FileEntry) = viewModelScope.launch {
        val res = mutate.invoke { m -> m.copy(files = m.files.filterNot { it.id == file.id }) }
        if (res is Outcome.Ok) blobRepo.deleteBlobs(listOf(file.blob))
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
        if (res is Outcome.Ok) blobRepo.deleteBlobs(freedBlobs)
    }

    fun uploadPicked(name: String, mime: String, size: Long, open: () -> InputStream) = viewModelScope.launch {
        _busy.value = true
        try {
            when (val up = blobRepo.upload(name, mime, size, open)) {
                is Outcome.Ok -> {
                    val cwd = stack.last()
                    mutate.invoke { m ->
                        m.copy(
                            files = m.files + FileEntry(
                                id = up.value.id,
                                blob = up.value.id,
                                encFileKey = up.value.encFileKey,
                                name = name,
                                mime = mime,
                                size = size,
                                folder = cwd,
                            ),
                        )
                    }
                }
                is Outcome.Err -> _message.value = "Upload failed"
            }
        } finally {
            _busy.value = false
        }
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

    private fun newId(): String = UUID.randomUUID().toString()

    private fun recompute() {
        val m = cache.value.value?.manifest
        val cwd = stack.last()
        val folders = m?.fileFolders?.filter { it.parent == cwd }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val files = m?.files?.filter { !it.trashed && it.folder == cwd }?.sortedBy { it.name.lowercase() } ?: emptyList()
        _state.value = FilesUi(false, false, folders, files, canGoBack = stack.size > 1)
    }
}
