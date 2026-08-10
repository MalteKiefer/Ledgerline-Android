package de.ledgerline.app.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.data.notes.NotesRepository
import de.ledgerline.app.domain.model.notes.Note
import de.ledgerline.app.domain.model.notes.NoteFolder
import de.ledgerline.app.domain.model.notes.NoteRow
import de.ledgerline.app.domain.model.notes.NoteTag
import de.ledgerline.app.domain.model.notes.NotesTrash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State for the Notes tab: folder tree, note rows, tags, search, and the create/edit/trash flow. */
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repo: NotesRepository,
) : ViewModel() {

    val folders: StateFlow<List<NoteFolder>> = repo.folders
    val notes: StateFlow<List<NoteRow>> = repo.notes
    val tags: StateFlow<List<NoteTag>> = repo.tags

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** null = all folders; a folder id filters the list. */
    private val _selectedFolder = MutableStateFlow<Int?>(null)
    val selectedFolder: StateFlow<Int?> = _selectedFolder.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<NoteRow>>(emptyList())
    val results: StateFlow<List<NoteRow>> = _results.asStateFlow()

    private var bootstrapped = false
    fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        repo.load()
        _refreshing.value = false
    }

    fun selectFolder(id: Int?) { _selectedFolder.value = id }
    fun folderName(id: Int?): String? = id?.let { fid -> folders.value.firstOrNull { it.id == fid }?.name }

    fun search(q: String) {
        _query.value = q
        viewModelScope.launch { _results.value = repo.search(q) }
    }
    fun clearSearch() { _query.value = ""; _results.value = emptyList() }

    suspend fun openNote(id: Int): Note? = repo.note(id)

    fun save(id: Int?, title: String, body: String, tags: List<String>, folderId: Int?, version: Int, done: (Boolean) -> Unit) =
        viewModelScope.launch {
            val note = if (id == null) repo.create(title, body, tags, folderId)
            else repo.update(id, title, body, tags, folderId, version)
            done(note != null)
        }

    fun delete(id: Int, done: () -> Unit = {}) = viewModelScope.launch { if (repo.delete(id)) done() }
    fun setFavorite(id: Int, on: Boolean) = viewModelScope.launch { repo.setFavorite(id, on) }
    fun setPinned(id: Int, on: Boolean) = viewModelScope.launch { repo.setPinned(id, on) }

    fun addFolder(name: String, parentId: Int?, done: (Boolean) -> Unit = {}) =
        viewModelScope.launch { done(repo.createFolder(name, parentId)) }
    fun renameFolder(id: Int, name: String, version: Int, done: (Boolean) -> Unit = {}) =
        viewModelScope.launch { done(repo.renameFolder(id, name, version)) }
    fun deleteFolder(id: Int, done: (Boolean) -> Unit = {}) =
        viewModelScope.launch { done(repo.deleteFolder(id)) }

    // ---- Trash ----
    suspend fun loadTrash(): NotesTrash? = repo.trash()
    fun restore(id: Int, done: () -> Unit) = viewModelScope.launch { if (repo.restore(id)) done() }
    fun force(id: Int, done: () -> Unit) = viewModelScope.launch { if (repo.force(id)) done() }
    fun restoreFolder(id: Int, done: () -> Unit) = viewModelScope.launch { if (repo.restoreFolder(id)) done() }
}
