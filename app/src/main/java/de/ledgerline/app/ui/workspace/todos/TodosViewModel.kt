package de.ledgerline.app.ui.workspace.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.domain.model.TodoItem
import de.ledgerline.app.domain.model.TodoList
import de.ledgerline.app.domain.usecase.LoadWorkspace
import de.ledgerline.app.domain.usecase.MutateWorkspace
import de.ledgerline.app.domain.workspace.TodoOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class TodosUi(
    val loading: Boolean = false,
    val error: Boolean = false,
    val items: List<TodoItem> = emptyList(),
)

@HiltViewModel
class TodosViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
    private val mutate: MutateWorkspace,
    settingsStore: SettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(TodosUi(loading = true))
    val state: StateFlow<TodosUi> = _state

    /** Whether opening a link shows the app chooser ("ask which browser"); default on. */
    val linkChooserEnabled: StateFlow<Boolean> = settingsStore.linkChooserEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _lists = MutableStateFlow<List<TodoList>>(emptyList())
    val lists: StateFlow<List<TodoList>> = _lists

    /** Current list filter; null = all lists. */
    private val _activeList = MutableStateFlow<String?>(null)
    val activeList: StateFlow<String?> = _activeList

    /** When true, the list shows only trashed todos (the trash view). */
    private val _showTrash = MutableStateFlow(false)
    val showTrash: StateFlow<Boolean> = _showTrash

    /** Number of trashed todos (drives the "Trash (N)" affordance). */
    private val _trashCount = MutableStateFlow(0)
    val trashCount: StateFlow<Int> = _trashCount

    /** Transient one-shot user message (failure); cleared once shown. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init {
        viewModelScope.launch {
            cache.value.collect { ws ->
                if (ws != null) recompute() else _state.value = TodosUi(loading = true)
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        if (load.invoke() is Outcome.Err) {
            _state.value = _state.value.copy(loading = false, error = true)
        }
    }

    fun setActiveList(id: String?) {
        _activeList.value = id
        recompute()
    }

    fun todoById(id: String): TodoItem? = cache.value.value?.manifest?.todos?.firstOrNull { it.id == id }

    // ---- Manifest mutations (the cache-flow collector recomputes the list automatically) ----

    fun addTodo(
        title: String,
        listId: String?,
        priority: String,
        due: String,
        description: String,
        url: String,
        tags: List<String>,
    ) = write { m -> TodoOps.addTodo(m, newId(), title, listId, priority, due, description, url, tags) }

    fun editTodo(
        id: String,
        title: String,
        listId: String?,
        priority: String,
        due: String,
        description: String,
        url: String,
        tags: List<String>,
    ) = write { m -> TodoOps.editTodo(m, id, title, listId, priority, due, description, url, tags) }

    fun toggleDone(id: String) = write { m -> TodoOps.toggleDone(m, id) }
    fun toggleMarked(id: String) = write { m -> TodoOps.toggleMarked(m, id) }
    fun trashTodo(id: String) = write { m -> TodoOps.trashTodo(m, id) }

    // ---- Trash view ----

    fun setTrash(show: Boolean) {
        _showTrash.value = show
        recompute()
    }

    fun toggleTrash() = setTrash(!_showTrash.value)

    fun restore(id: String) = write { m -> TodoOps.restoreTodo(m, id) }
    fun deleteForever(id: String) = write { m -> TodoOps.removeTodo(m, id) }
    fun emptyTrash() = write { m -> TodoOps.emptyTrashTodos(m) }

    fun addList(name: String) = write { m -> TodoOps.addList(m, newId(), name) }
    fun renameList(id: String, name: String) = write { m -> TodoOps.renameList(m, id, name) }

    fun deleteList(id: String) = viewModelScope.launch {
        val res = mutate.invoke { m -> TodoOps.deleteList(m, id) }
        if (res is Outcome.Err) _message.value = "Save failed"
        else if (_activeList.value == id) setActiveList(null)
    }

    fun clearMessage() { _message.value = null }

    private inline fun write(crossinline mutation: (de.ledgerline.app.domain.model.WorkspaceManifest) -> de.ledgerline.app.domain.model.WorkspaceManifest) =
        viewModelScope.launch {
            if (mutate.invoke { m -> mutation(m) } is Outcome.Err) _message.value = "Save failed"
        }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun priorityRank(priority: String): Int = when (priority.lowercase()) {
        "urgent" -> 0
        "high" -> 1
        "normal" -> 2
        "low" -> 3
        else -> 2
    }

    private fun recompute() {
        val m = cache.value.value?.manifest
        _lists.value = m?.todoLists.orEmpty()
        val all = m?.todos.orEmpty()
        _trashCount.value = all.count { it.trashed }
        val filter = _activeList.value
        val items = if (_showTrash.value) {
            // Trash shows all trashed todos regardless of the list filter.
            all.filter { it.trashed }
                .sortedWith(compareBy({ priorityRank(it.priority) }, { it.title.lowercase() }))
        } else {
            all.filter { !it.trashed && (filter == null || it.listId == filter) }
                .sortedWith(
                    compareBy({ it.done }, { priorityRank(it.priority) }, { it.title.lowercase() }),
                )
        }
        _state.value = TodosUi(false, false, items)
    }
}
