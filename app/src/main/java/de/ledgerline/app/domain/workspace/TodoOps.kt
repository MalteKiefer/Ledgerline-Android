package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.TodoItem
import de.ledgerline.app.domain.model.TodoList
import de.ledgerline.app.domain.model.WorkspaceManifest

/**
 * Pure manifest transforms for todo & todo-list management. Each returns a new
 * [WorkspaceManifest]; mutating an unknown id is a safe no-op. Mirrors the web
 * `vaultWorkspace` todo ops (see `AlbumOps` for the same style).
 */
object TodoOps {

    fun addTodo(
        m: WorkspaceManifest,
        id: String,
        title: String,
        listId: String?,
        priority: String,
        due: String,
        description: String,
        url: String,
        tags: List<String>,
    ): WorkspaceManifest {
        val todo = TodoItem(
            id = id,
            listId = listId,
            title = title.trim(),
            description = description.trim(),
            url = url.trim(),
            priority = priority,
            marked = false,
            due = due.trim(),
            done = false,
            trashed = false,
            tags = tags,
        )
        return m.copy(todos = m.todos + todo)
    }

    fun editTodo(
        m: WorkspaceManifest,
        id: String,
        title: String,
        listId: String?,
        priority: String,
        due: String,
        description: String,
        url: String,
        tags: List<String>,
    ): WorkspaceManifest = updateTodo(m, id) {
        it.copy(
            title = title.trim(),
            listId = listId,
            priority = priority,
            due = due.trim(),
            description = description.trim(),
            url = url.trim(),
            tags = tags,
        )
    }

    fun toggleDone(m: WorkspaceManifest, id: String): WorkspaceManifest =
        updateTodo(m, id) { it.copy(done = !it.done) }

    fun toggleMarked(m: WorkspaceManifest, id: String): WorkspaceManifest =
        updateTodo(m, id) { it.copy(marked = !it.marked) }

    fun trashTodo(m: WorkspaceManifest, id: String): WorkspaceManifest =
        updateTodo(m, id) { it.copy(trashed = true) }

    /** Move a trashed todo back to the active list. */
    fun restoreTodo(m: WorkspaceManifest, id: String): WorkspaceManifest =
        updateTodo(m, id) { it.copy(trashed = false) }

    /** Delete a todo forever: remove it from the list entirely. Unknown id = no-op. */
    fun removeTodo(m: WorkspaceManifest, id: String): WorkspaceManifest =
        m.copy(todos = m.todos.filterNot { it.id == id })

    /** Empty the trash: drop every trashed todo (keeps the live ones). */
    fun emptyTrashTodos(m: WorkspaceManifest): WorkspaceManifest =
        m.copy(todos = m.todos.filterNot { it.trashed })

    fun addList(m: WorkspaceManifest, id: String, name: String): WorkspaceManifest =
        m.copy(todoLists = m.todoLists + TodoList(id = id, name = name.trim()))

    fun renameList(m: WorkspaceManifest, id: String, name: String): WorkspaceManifest =
        m.copy(todoLists = m.todoLists.map { if (it.id == id) it.copy(name = name.trim()) else it })

    /** Remove the list and orphan its todos (`listId == id` → `listId = null`). */
    fun deleteList(m: WorkspaceManifest, id: String): WorkspaceManifest = m.copy(
        todoLists = m.todoLists.filterNot { it.id == id },
        todos = m.todos.map { if (it.listId == id) it.copy(listId = null) else it },
    )

    private inline fun updateTodo(
        m: WorkspaceManifest,
        id: String,
        transform: (TodoItem) -> TodoItem,
    ): WorkspaceManifest =
        m.copy(todos = m.todos.map { if (it.id == id) transform(it) else it })
}
