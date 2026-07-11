package de.ledgerline.app.ui.workspace.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.domain.model.Bookmark
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.domain.model.WorkspaceManifest
import de.ledgerline.app.domain.usecase.LoadWorkspace
import de.ledgerline.app.domain.usecase.MutateWorkspace
import de.ledgerline.app.domain.workspace.BookmarkOps
import de.ledgerline.app.domain.workspace.WorkspaceSearch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class BookmarksUi(
    val loading: Boolean = false,
    val error: Boolean = false,
    val items: List<Bookmark> = emptyList(),
)

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
    private val mutate: MutateWorkspace,
    settingsStore: SettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(BookmarksUi(loading = true))
    val state: StateFlow<BookmarksUi> = _state

    /** Whether opening a link shows the app chooser ("ask which browser"); default on. */
    val linkChooserEnabled: StateFlow<Boolean> = settingsStore.linkChooserEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _folders = MutableStateFlow<List<NamedFolder>>(emptyList())
    val folders: StateFlow<List<NamedFolder>> = _folders

    /** Current folder filter; null = all folders. */
    private val _activeFolder = MutableStateFlow<String?>(null)
    val activeFolder: StateFlow<String?> = _activeFolder

    /** When true, the list shows only trashed bookmarks (the trash view). */
    private val _showTrash = MutableStateFlow(false)
    val showTrash: StateFlow<Boolean> = _showTrash

    /** Number of trashed bookmarks (drives the "Trash (N)" affordance). */
    private val _trashCount = MutableStateFlow(0)
    val trashCount: StateFlow<Int> = _trashCount

    /** Transient one-shot user message (failure); cleared once shown. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Live text-search query; filters the active (non-trash) list. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    init {
        viewModelScope.launch {
            cache.value.collect { ws ->
                if (ws != null) recompute() else _state.value = BookmarksUi(loading = true)
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        if (load.invoke() is Outcome.Err) {
            _state.value = _state.value.copy(loading = false, error = true)
        }
    }

    fun setActiveFolder(id: String?) {
        _activeFolder.value = id
        recompute()
    }

    fun bookmarkById(id: String): Bookmark? =
        cache.value.value?.manifest?.bookmarks?.firstOrNull { it.id == id }

    // ---- Manifest mutations (the cache-flow collector recomputes the list automatically) ----

    fun addBookmark(url: String, title: String, description: String, folderId: String?, tags: List<String>) =
        write { m -> BookmarkOps.addBookmark(m, newId(), url, title, description, folderId, tags) }

    fun editBookmark(id: String, url: String, title: String, description: String, folderId: String?, tags: List<String>) =
        write { m -> BookmarkOps.editBookmark(m, id, url, title, description, folderId, tags) }

    fun toggleFavorite(id: String) = write { m -> BookmarkOps.toggleFavorite(m, id) }
    fun toggleReadLater(id: String) = write { m -> BookmarkOps.toggleReadLater(m, id) }
    fun trashBookmark(id: String) = write { m -> BookmarkOps.trashBookmark(m, id) }

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

    fun restore(id: String) = write { m -> BookmarkOps.restoreBookmark(m, id) }
    fun deleteForever(id: String) = write { m -> BookmarkOps.removeBookmark(m, id) }
    fun emptyTrash() = write { m -> BookmarkOps.emptyTrashBookmarks(m) }

    fun addFolder(name: String) = write { m -> BookmarkOps.addFolder(m, newId(), name) }
    fun renameFolder(id: String, name: String) = write { m -> BookmarkOps.renameFolder(m, id, name) }

    fun deleteFolder(id: String) = viewModelScope.launch {
        val res = mutate.invoke { m -> BookmarkOps.deleteFolder(m, id) }
        if (res is Outcome.Err) _message.value = "Save failed"
        else if (_activeFolder.value == id) setActiveFolder(null)
    }

    fun clearMessage() { _message.value = null }

    private inline fun write(crossinline mutation: (WorkspaceManifest) -> WorkspaceManifest) =
        viewModelScope.launch {
            if (mutate.invoke { m -> mutation(m) } is Outcome.Err) _message.value = "Save failed"
        }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun recompute() {
        val m = cache.value.value?.manifest
        _folders.value = m?.bookmarkFolders.orEmpty()
        val all = m?.bookmarks.orEmpty()
        _trashCount.value = all.count { it.trashed }
        val filter = _activeFolder.value
        val items = if (_showTrash.value) {
            // Trash shows all trashed bookmarks regardless of the folder filter.
            all.filter { it.trashed }.sortedBy { it.title.ifBlank { it.url }.lowercase() }
        } else {
            all.filter { !it.trashed && (filter == null || it.folderId == filter) && WorkspaceSearch.matches(it, _query.value) }
                .sortedBy { it.title.ifBlank { it.url }.lowercase() }
        }
        _state.value = BookmarksUi(false, false, items)
    }
}
