package de.ledgerline.app.ui.workspace.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.Bookmark
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookmarkGroup(val folderName: String?, val bookmarks: List<Bookmark>)
data class BookmarksUi(val loading: Boolean = false, val error: Boolean = false, val groups: List<BookmarkGroup> = emptyList())

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
) : ViewModel() {
    private val _state = MutableStateFlow(BookmarksUi(loading = true))
    val state: StateFlow<BookmarksUi> = _state

    init { if (cache.value.value == null) refresh() else recompute() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        when (load.invoke()) {
            is Outcome.Ok -> recompute()
            is Outcome.Err -> _state.value = BookmarksUi(loading = false, error = true)
        }
    }

    private fun recompute() {
        val m = cache.value.value?.manifest
        val folderName = m?.bookmarkFolders?.associate { it.id to it.name }.orEmpty()
        val visible = m?.bookmarks.orEmpty().filter { !it.trashed }
        val byFolder = visible.filter { it.folderId != null }
            .groupBy { it.folderId }
            .toSortedMap(compareBy { folderName[it] ?: "" })
            .map { (fid, list) -> BookmarkGroup(folderName[fid] ?: "?", list.sortedBy { it.title.lowercase() }) }
        val ungrouped = visible.filter { it.folderId == null }
        val groups = byFolder + if (ungrouped.isNotEmpty()) listOf(BookmarkGroup(null, ungrouped.sortedBy { it.title.lowercase() })) else emptyList()
        _state.value = BookmarksUi(false, false, groups)
    }
}
