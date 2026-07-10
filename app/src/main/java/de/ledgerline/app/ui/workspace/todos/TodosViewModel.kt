package de.ledgerline.app.ui.workspace.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.TodoItem
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TodoSection(val listName: String, val items: List<TodoItem>)
data class TodosUi(val loading: Boolean = false, val error: Boolean = false, val sections: List<TodoSection> = emptyList())

@HiltViewModel
class TodosViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
) : ViewModel() {
    private val _state = MutableStateFlow(TodosUi(loading = true))
    val state: StateFlow<TodosUi> = _state

    init { if (cache.value.value == null) refresh() else recompute() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        when (load.invoke()) {
            is Outcome.Ok -> recompute()
            is Outcome.Err -> _state.value = TodosUi(loading = false, error = true)
        }
    }

    private fun recompute() {
        val m = cache.value.value?.manifest
        val listName = m?.todoLists?.associate { it.id to it.name }.orEmpty()
        val visible = m?.todos.orEmpty().filter { !it.trashed }
        val sections = listName.entries.map { (id, name) ->
            TodoSection(name, visible.filter { it.listId == id }
                .sortedWith(compareBy<TodoItem> { it.done }.thenBy { it.title.lowercase() }))
        }.filter { it.items.isNotEmpty() }
        val orphans = visible.filter { it.listId == null || it.listId !in listName }
        val all = sections + if (orphans.isNotEmpty()) listOf(TodoSection("Other", orphans.sortedWith(compareBy<TodoItem> { it.done }.thenBy { it.title.lowercase() }))) else emptyList()
        _state.value = TodosUi(false, false, all)
    }
}
