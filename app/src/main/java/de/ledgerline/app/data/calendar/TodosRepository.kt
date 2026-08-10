package de.ledgerline.app.data.calendar

import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.data.remote.CalendarApi
import de.ledgerline.app.data.remote.NetworkFactory
import de.ledgerline.app.domain.model.calendar.Calendar
import de.ledgerline.app.domain.model.calendar.CalendarTodo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data layer for the task-list (VTODO) feature. Online-only (mirrors Files/Finance read model): [lists]
 * are the owner's VTODO calendars, [todos] every task across them. Writes are per-record REST with the
 * DAV-native `etag` for optimistic concurrency (PUT → 409 on a stale etag); on success we reload so
 * server-rolled recurrence (completing a recurring task rolls DUE forward) and sort order are exact.
 */
@Singleton
class TodosRepository @Inject constructor(
    private val sessionHolder: SessionHolder,
) {
    private val _lists = MutableStateFlow<List<Calendar>>(emptyList())
    val lists: StateFlow<List<Calendar>> = _lists.asStateFlow()

    private val _todos = MutableStateFlow<List<CalendarTodo>>(emptyList())
    val todos: StateFlow<List<CalendarTodo>> = _todos.asStateFlow()

    private fun api(): CalendarApi {
        val s = sessionHolder.get() ?: error("no session")
        return NetworkFactory.createCalendar(s.baseUrl, tokenProvider = { s.token }, pin = s.spkiPin)
    }

    /** Refresh the VTODO lists and all their tasks (expand=1 → next_due for recurring tasks). */
    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val data = api().data()
            val lists = data.body()?.calendars.orEmpty().filter { it.component == "VTODO" }
            _lists.value = lists
            val todos = api().todos(expand = 1).body()?.todos.orEmpty()
                .sortedWith(compareBy({ it.done }, { it.sortOrder }, { it.due ?: "￿" }))
            _todos.value = todos
            true
        }.getOrDefault(false)
    }

    /** Ensure at least one task list exists; creates a default one if none, then reloads. */
    suspend fun ensureList(defaultName: String): Boolean = withContext(Dispatchers.IO) {
        if (_lists.value.isNotEmpty()) return@withContext true
        createListNamed(defaultName)
    }

    /** Create a new VTODO task list, then reload. */
    suspend fun createListNamed(name: String): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            api().createCalendar(buildJsonObject { put("name", name.trim()); put("component", "VTODO") }).isSuccessful
        }.getOrDefault(false)
        if (ok) load(); ok
    }

    suspend fun create(body: JsonObject): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching { api().createTodo(body).isSuccessful }.getOrDefault(false)
        if (ok) load(); ok
    }

    suspend fun update(id: String, body: JsonObject): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching { api().updateTodo(id, body).isSuccessful }.getOrDefault(false)
        if (ok) load(); ok
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching { api().deleteTodo(id).isSuccessful }.getOrDefault(false)
        if (ok) load(); ok
    }

    /**
     * Toggle a task's completion. The PUT rebuilds the VTODO from the input, so we must resend the
     * task's other fields (due/description/priority/categories) or they'd be cleared — only status /
     * percent / completed change.
     */
    suspend fun setDone(t: CalendarTodo, done: Boolean): Boolean {
        val body = buildJsonObject {
            put("calendar_id", t.calendar)
            put("summary", t.summary ?: "")
            put("description", if (t.description.isNullOrBlank()) JsonNull else JsonPrimitive(t.description))
            put("due", if (t.due.isNullOrBlank()) JsonNull else JsonPrimitive(t.due))
            put("dtstart", if (t.dtstart.isNullOrBlank()) JsonNull else JsonPrimitive(t.dtstart))
            put("all_day", t.allDay)
            put("priority", t.priority?.let { JsonPrimitive(it) } ?: JsonNull)
            put("rrule", if (t.rrule.isNullOrBlank()) JsonNull else JsonPrimitive(t.rrule))
            put("related_to", if (t.relatedTo.isNullOrBlank()) JsonNull else JsonPrimitive(t.relatedTo))
            put("categories", buildJsonArray { t.categories.forEach { add(it) } })
            if (t.etag.isNotBlank()) put("etag", t.etag)
            if (done) {
                put("status", "COMPLETED")
                put("percent_complete", 100)
                put("completed", Instant.now().toString())
            } else {
                put("status", "NEEDS-ACTION")
                put("percent_complete", 0)
                put("completed", JsonNull)
            }
        }
        return update(t.id, body)
    }

    /** Persist a manual ordering of task ids (own tasks only). */
    suspend fun reorder(ids: List<String>): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            api().reorder(buildJsonObject { put("order", JsonArray(ids.map { JsonPrimitive(it) })) }).isSuccessful
        }.getOrDefault(false)
        if (ok) load(); ok
    }

    /**
     * Build a full CalendarTodoInput body from editor fields (nulls clear). Covers web parity:
     * dtstart, due, status, priority, percent, rrule, categories (tags), related_to (subtask parent).
     */
    fun todoBody(
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
        relatedTo: String?,
        etag: String?,
        completed: String? = null,
    ): JsonObject = buildJsonObject {
        put("calendar_id", calendarId)
        put("summary", summary.trim())
        val desc = description.trim()
        put("description", if (desc.isEmpty()) JsonNull else JsonPrimitive(desc))
        put("dtstart", if (dtstart.isNullOrBlank()) JsonNull else JsonPrimitive(dtstart))
        put("due", if (due.isNullOrBlank()) JsonNull else JsonPrimitive(due))
        put("all_day", allDay)
        put("status", status)
        put("priority", priority?.let { JsonPrimitive(it) } ?: JsonNull)
        val pct = if (status == "COMPLETED") (percent ?: 100) else percent
        put("percent_complete", pct?.let { JsonPrimitive(it) } ?: JsonNull)
        put("rrule", if (rrule.isNullOrBlank()) JsonNull else JsonPrimitive(rrule))
        put("related_to", if (relatedTo.isNullOrBlank()) JsonNull else JsonPrimitive(relatedTo))
        put("categories", buildJsonArray { categories.forEach { add(it) } })
        if (status == "COMPLETED") put("completed", if (completed.isNullOrBlank()) Instant.now().toString() else completed)
        if (!etag.isNullOrBlank()) put("etag", etag)
    }
}
