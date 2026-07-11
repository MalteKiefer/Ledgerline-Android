package de.ledgerline.app.ui.workspace.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.model.WorkspaceManifest
import de.ledgerline.app.domain.usecase.LoadWorkspace
import de.ledgerline.app.domain.usecase.MutateWorkspace
import de.ledgerline.app.domain.workspace.NoteOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
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
    private val mutate: MutateWorkspace,
) : ViewModel() {
    private val _state = MutableStateFlow(NotesUi(loading = true))
    val state: StateFlow<NotesUi> = _state

    /** Transient one-shot user message (failure); cleared once shown. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

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

    // ---- Manifest mutations (the cache-flow collector recomputes the list automatically) ----

    /**
     * Create a new (empty) note and return its id so the caller can immediately open
     * the editor on it. The id is generated synchronously; the persist runs async.
     */
    fun addNote(): String {
        val id = newId()
        write { m -> NoteOps.addNote(m, id, nowIso()) }
        return id
    }

    fun updateNote(id: String, title: String, content: String) =
        write { m -> NoteOps.updateNote(m, id, title, content, nowIso()) }

    fun togglePin(id: String) = write { m -> NoteOps.togglePin(m, id) }
    fun trashNote(id: String) = write { m -> NoteOps.trashNote(m, id) }

    fun clearMessage() { _message.value = null }

    private inline fun write(crossinline mutation: (WorkspaceManifest) -> WorkspaceManifest) =
        viewModelScope.launch {
            if (mutate.invoke { m -> mutation(m) } is Outcome.Err) _message.value = "Save failed"
        }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

    private fun recompute() {
        val notes = cache.value.value?.manifest?.notes.orEmpty()
            .filter { !it.trashed }
            .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated ?: "" })
        _state.value = NotesUi(false, false, notes)
    }
}
