package de.ledgerline.app.ui.workspace.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotesUi(
    val loading: Boolean = false,
    val error: Boolean = false,
    val notes: List<Note> = emptyList(),
)

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
) : ViewModel() {
    private val _state = MutableStateFlow(NotesUi(loading = true))
    val state: StateFlow<NotesUi> = _state

    init {
        viewModelScope.launch {
            cache.value.collect { ws ->
                if (ws != null) recompute() else _state.value = NotesUi(loading = true)
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = false)
        if (load.invoke() is Outcome.Err) {
            _state.value = _state.value.copy(loading = false, error = true)
        }
    }

    fun noteById(id: String): Note? = cache.value.value?.manifest?.notes?.firstOrNull { it.id == id }

    private fun recompute() {
        val notes = cache.value.value?.manifest?.notes.orEmpty()
            .filter { !it.trashed }
            .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated ?: "" })
        _state.value = NotesUi(false, false, notes)
    }
}
