package de.ledgerline.app.ui.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.data.calendar.TodosRepository
import de.ledgerline.app.domain.model.calendar.Calendar
import de.ledgerline.app.domain.model.calendar.CalendarTodo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State for the "Aufgaben" (VTODO task-list) tab: task lists, tasks, and the create/edit/complete flow. */
@HiltViewModel
class TodosViewModel @Inject constructor(
    private val repo: TodosRepository,
) : ViewModel() {

    val lists: StateFlow<List<Calendar>> = repo.lists
    val todos: StateFlow<List<CalendarTodo>> = repo.todos

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** null = all lists. */
    private val _selectedList = MutableStateFlow<String?>(null)
    val selectedList: StateFlow<String?> = _selectedList.asStateFlow()

    private val _showDone = MutableStateFlow(false)
    val showDone: StateFlow<Boolean> = _showDone.asStateFlow()

    private var bootstrapped = false

    /** First-run: load, and if the account has no task list yet, create a default one. */
    fun bootstrap(defaultListName: String) {
        if (bootstrapped) return
        bootstrapped = true
        viewModelScope.launch {
            repo.load()
            repo.ensureList(defaultListName)
        }
    }

    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        repo.load()
        _refreshing.value = false
    }

    fun selectList(id: String?) { _selectedList.value = id }
    fun toggleShowDone() { _showDone.value = !_showDone.value }

    fun todo(id: String): CalendarTodo? = todos.value.firstOrNull { it.id == id }
    fun listName(id: String): String? = lists.value.firstOrNull { it.id == id }?.name

    fun setDone(t: CalendarTodo, done: Boolean) = viewModelScope.launch { repo.setDone(t, done) }
    fun delete(id: String, done: () -> Unit = {}) = viewModelScope.launch { if (repo.delete(id)) done() }

    fun save(
        id: String?,
        calendarId: String,
        summary: String,
        description: String,
        dtstart: String?,
        due: String?,
        allDay: Boolean,
        status: String,
        priority: Int?,
        percent: Int?,
        rrule: String?,
        categories: List<String>,
        parentUid: String?,
        alarmMinutes: Int?,
        etag: String?,
        done: (Boolean) -> Unit,
    ) = viewModelScope.launch {
        val prev = id?.let { todo(it) }
        val body = repo.todoBody(
            calendarId, summary, description, dtstart, due, allDay, status, priority, percent, rrule,
            categories, parentUid, alarmMinutes, etag, completed = prev?.completedAt,
        )
        val ok = if (id == null) repo.create(body) else repo.update(id, body)
        done(ok)
    }

    fun addList(name: String, done: (Boolean) -> Unit) = viewModelScope.launch {
        done(repo.createListNamed(name))
    }
    fun renameList(id: String, name: String, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.renameList(id, name)) }
    fun deleteList(id: String, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.deleteList(id)) }

    fun reorder(ids: List<String>) = viewModelScope.launch { repo.reorder(ids) }

    // ---- Task-list sharing ----
    suspend fun shares() = repo.shares()
    fun shareList(calendarId: String, email: String, role: String, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(repo.createShare(calendarId, email, role)) }
    fun unshare(id: Int, done: (Boolean) -> Unit) = viewModelScope.launch { done(repo.deleteShare(id)) }
}
