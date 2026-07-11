package de.ledgerline.app.domain.workspace

import de.ledgerline.app.domain.model.TodoItem
import de.ledgerline.app.domain.model.TodoList
import de.ledgerline.app.domain.model.WorkspaceManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoOpsTest {

    private fun manifest(
        todos: List<TodoItem> = emptyList(),
        lists: List<TodoList> = emptyList(),
    ) = WorkspaceManifest(todos = todos, todoLists = lists)

    @Test
    fun addTodo_appends_with_correct_defaults_and_trims() {
        val out = TodoOps.addTodo(
            manifest(), id = "t1", title = "  Buy milk  ", listId = "l1",
            priority = "high", due = "  2026-01-02  ", description = "  note  ", url = "  http://x  ",
        )

        assertEquals(1, out.todos.size)
        val t = out.todos.first()
        assertEquals("t1", t.id)
        assertEquals("Buy milk", t.title)
        assertEquals("l1", t.listId)
        assertEquals("high", t.priority)
        assertEquals("2026-01-02", t.due)
        assertEquals("note", t.description)
        assertEquals("http://x", t.url)
        assertFalse(t.marked)
        assertFalse(t.done)
        assertFalse(t.trashed)
    }

    @Test
    fun addTodo_allows_null_list() {
        val out = TodoOps.addTodo(manifest(), "t1", "X", null, "normal", "", "", "")
        assertNull(out.todos.first().listId)
    }

    @Test
    fun editTodo_updates_fields_and_trims_title() {
        val m = manifest(todos = listOf(TodoItem(id = "t1", title = "Old", listId = "l1")))

        val out = TodoOps.editTodo(
            m, id = "t1", title = "  New  ", listId = "l2",
            priority = "urgent", due = "2026-05-05", description = "d", url = "u",
        )

        val t = out.todos.first()
        assertEquals("New", t.title)
        assertEquals("l2", t.listId)
        assertEquals("urgent", t.priority)
        assertEquals("2026-05-05", t.due)
        assertEquals("d", t.description)
        assertEquals("u", t.url)
    }

    @Test
    fun toggleDone_flips_flag() {
        val m = manifest(todos = listOf(TodoItem(id = "t1", done = false)))
        val on = TodoOps.toggleDone(m, "t1")
        assertTrue(on.todos.first().done)
        assertFalse(TodoOps.toggleDone(on, "t1").todos.first().done)
    }

    @Test
    fun toggleMarked_flips_flag() {
        val m = manifest(todos = listOf(TodoItem(id = "t1", marked = false)))
        val on = TodoOps.toggleMarked(m, "t1")
        assertTrue(on.todos.first().marked)
        assertFalse(TodoOps.toggleMarked(on, "t1").todos.first().marked)
    }

    @Test
    fun trashTodo_sets_trashed_true() {
        val m = manifest(todos = listOf(TodoItem(id = "t1", trashed = false)))
        assertTrue(TodoOps.trashTodo(m, "t1").todos.first().trashed)
    }

    @Test
    fun addList_appends_trimmed_name() {
        val out = TodoOps.addList(manifest(), "l1", "  Home  ")
        assertEquals(listOf("l1"), out.todoLists.map { it.id })
        assertEquals("Home", out.todoLists.first().name)
    }

    @Test
    fun renameList_trims_name() {
        val m = manifest(lists = listOf(TodoList(id = "l1", name = "Old")))
        assertEquals("New Name", TodoOps.renameList(m, "l1", "  New Name  ").todoLists.first().name)
    }

    @Test
    fun deleteList_removes_list_and_orphans_its_todos() {
        val m = manifest(
            todos = listOf(
                TodoItem(id = "t1", listId = "l1"),
                TodoItem(id = "t2", listId = "l2"),
                TodoItem(id = "t3", listId = "l1"),
            ),
            lists = listOf(TodoList(id = "l1", name = "A"), TodoList(id = "l2", name = "B")),
        )

        val out = TodoOps.deleteList(m, "l1")

        assertEquals(listOf("l2"), out.todoLists.map { it.id })
        assertNull(out.todos.first { it.id == "t1" }.listId) // orphaned
        assertNull(out.todos.first { it.id == "t3" }.listId) // orphaned
        assertEquals("l2", out.todos.first { it.id == "t2" }.listId) // untouched
    }

    @Test
    fun unknown_id_is_safe_noop() {
        val m = manifest(
            todos = listOf(TodoItem(id = "t1", title = "T", listId = "l1")),
            lists = listOf(TodoList(id = "l1", name = "A")),
        )

        assertEquals(m.todos, TodoOps.editTodo(m, "zzz", "X", null, "high", "", "", "").todos)
        assertEquals(m.todos, TodoOps.toggleDone(m, "zzz").todos)
        assertEquals(m.todos, TodoOps.toggleMarked(m, "zzz").todos)
        assertEquals(m.todos, TodoOps.trashTodo(m, "zzz").todos)
        assertEquals(m.todoLists, TodoOps.renameList(m, "zzz", "X").todoLists)
        // deleteList of an unknown id leaves both lists and todos untouched
        assertEquals(m.todoLists, TodoOps.deleteList(m, "zzz").todoLists)
        assertEquals(m.todos, TodoOps.deleteList(m, "zzz").todos)
    }
}
