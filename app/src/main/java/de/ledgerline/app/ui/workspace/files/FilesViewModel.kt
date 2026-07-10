package de.ledgerline.app.ui.workspace.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FilesUi(
    val loading: Boolean = false,
    val error: Boolean = false,
    val folders: List<NamedFolder> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val canGoBack: Boolean = false,
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
) : ViewModel() {
    private val stack = ArrayDeque<String?>().apply { addLast(null) }   // current folder = last
    private val _state = MutableStateFlow(FilesUi(loading = true))
    val state: StateFlow<FilesUi> = _state

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

    private fun recompute() {
        val m = cache.value.value?.manifest
        val cwd = stack.last()
        val folders = m?.fileFolders?.filter { it.parent == cwd }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val files = m?.files?.filter { !it.trashed && it.folder == cwd }?.sortedBy { it.name.lowercase() } ?: emptyList()
        _state.value = FilesUi(false, false, folders, files, canGoBack = stack.size > 1)
    }
}
