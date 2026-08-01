package de.ledgerline.app.ui.workspace.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.data.DateFormatPref
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.model.WorkspaceManifest
import de.ledgerline.app.domain.usecase.LoadWorkspace
import de.ledgerline.app.domain.usecase.MutateWorkspace
import de.ledgerline.app.domain.workspace.NoteOps
import de.ledgerline.app.domain.workspace.Tags
import de.ledgerline.app.domain.workspace.WorkspaceSearch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
    private val workspaceRepo: de.ledgerline.app.data.WorkspaceRepository,
    private val history: de.ledgerline.app.data.StoreHistoryRepository,
    settingsStore: SettingsStore,
) : ViewModel() {

    // ---- Notes version history / recovery (v1.536) ----
    suspend fun historyVersions() = history.list(de.ledgerline.app.data.StoreHistoryRepository.Store.NOTES)
    suspend fun recoverVersion(version: Int): Int {
        val v = history.fetch(de.ledgerline.app.data.StoreHistoryRepository.Store.NOTES, version) ?: return -1
        val n = workspaceRepo.recoverNotesFromHistoryRoot(v.ciphertext)
        if (n > 0) load.invoke()
        return n
    }
    private val _state = MutableStateFlow(NotesUi(loading = true))
    val state: StateFlow<NotesUi> = _state

    /** Chosen date display format, for the detail screen's "updated" timestamp. */
    val dateFormat: StateFlow<DateFormatPref> = settingsStore.dateFormat
        .stateIn(viewModelScope, SharingStarted.Eagerly, DateFormatPref.SYSTEM)

    /** When true, the list shows only trashed notes (the trash view). */
    private val _showTrash = MutableStateFlow(false)
    val showTrash: StateFlow<Boolean> = _showTrash

    /** Number of trashed notes (drives the "Trash (N)" affordance). */
    private val _trashCount = MutableStateFlow(0)
    val trashCount: StateFlow<Int> = _trashCount

    /** Transient one-shot user message (failure); cleared once shown. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Live text-search query; filters the active (non-trash) list. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** Sorted distinct union of tags across non-trashed notes (drives filter chips). */
    private val _allTags = MutableStateFlow<List<String>>(emptyList())
    val allTags: StateFlow<List<String>> = _allTags

    /** Current tag filter; null = all tags. */
    private val _activeTag = MutableStateFlow<String?>(null)
    val activeTag: StateFlow<String?> = _activeTag

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
     * A fresh, NOT-yet-persisted blank note. The editor opens on it immediately and
     * persists on first [saveNote] — so creation doesn't wait on a network round-trip
     * populating the cache (the old flow opened on an id the cache didn't have yet, so
     * the FAB appeared to do nothing).
     */
    fun newBlankNote(): Note = Note(id = newId(), updated = nowIso())

    /**
     * Persist an edit: upsert by id (append if new, update if it exists). A note that
     * is still completely blank is discarded (no empty notes are created).
     */
    fun saveNote(id: String, title: String, content: String, tags: List<String>) {
        if (title.isBlank() && content.isBlank()) return
        write { m -> NoteOps.upsertNote(m, id, title, content, nowIso(), tags) }
    }

    fun togglePin(id: String) = write { m -> NoteOps.togglePin(m, id) }
    fun trashNote(id: String) = write { m -> NoteOps.trashNote(m, id) }

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

    fun setActiveTag(tag: String?) {
        _activeTag.value = tag
        recompute()
    }

    fun restore(id: String) = write { m -> NoteOps.restoreNote(m, id) }
    fun deleteForever(id: String) = write { m -> NoteOps.removeNote(m, id) }
    fun emptyTrash() = write { m -> NoteOps.emptyTrashNotes(m) }

    fun clearMessage() { _message.value = null }

    private inline fun write(crossinline mutation: (WorkspaceManifest) -> WorkspaceManifest) =
        viewModelScope.launch {
            if (mutate.invoke { m -> mutation(m) } is Outcome.Err) _message.value = "Save failed"
        }

    private fun newId(): String = de.ledgerline.app.core.Ids.newId()

    private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

    private fun recompute() {
        val all = cache.value.value?.manifest?.notes.orEmpty()
        _trashCount.value = all.count { it.trashed }
        _allTags.value = Tags.union(all.filter { !it.trashed }.map { it.tags })
        val tag = _activeTag.value
        val notes = if (_showTrash.value) {
            all.filter { it.trashed }.sortedByDescending { it.updated ?: "" }
        } else {
            all.filter {
                !it.trashed && WorkspaceSearch.matches(it, _query.value) &&
                    (tag == null || Tags.contains(it.tags, tag))
            }
                .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated ?: "" })
        }
        _state.value = NotesUi(false, false, notes)
    }
}
