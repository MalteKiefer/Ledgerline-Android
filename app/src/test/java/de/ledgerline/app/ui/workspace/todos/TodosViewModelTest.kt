package de.ledgerline.app.ui.workspace.todos

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.domain.model.*
import de.ledgerline.app.domain.usecase.LoadWorkspace
import de.ledgerline.app.domain.usecase.MutateWorkspace
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TodosViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val settingsStore = mockk<SettingsStore>(relaxed = true) {
        every { linkChooserEnabled } returns flowOf(true)
    }

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun ws() = Workspace(
        WorkspaceManifest(
            todoLists = listOf(TodoList(id = "l1", name = "Home"), TodoList(id = "l2", name = "Work")),
            todos = listOf(
                TodoItem(id = "t1", listId = "l1", title = "Done one", done = true, tags = listOf("chore")),
                TodoItem(id = "t2", listId = "l1", title = "Open one", done = false, priority = "high", tags = listOf("Urgent")),
                TodoItem(id = "t3", listId = "l1", title = "Gone", trashed = true, tags = listOf("secret")),
                TodoItem(id = "t4", listId = "l2", title = "Work item", done = false, tags = listOf("urgent")),
            ),
        ),
        version = 1,
    )
    private val cache = WorkspaceCache()

    // Fake load: populates the cache (as LoadWorkspaceImpl would) then returns Ok.
    private val load = object : LoadWorkspace {
        override suspend fun invoke(): Outcome<Workspace> {
            val w = ws()
            cache.set(w)
            return Outcome.Ok(w)
        }
    }

    // Fake mutate: applies the transform to the cached manifest and republishes it.
    private val mutate = object : MutateWorkspace {
        override suspend fun invoke(m: (WorkspaceManifest) -> WorkspaceManifest): Outcome<Workspace> {
            val cur = cache.value.value ?: return Outcome.Err(de.ledgerline.app.core.ErrorKind.UNKNOWN)
            val next = Workspace(m(cur.manifest), cur.version + 1)
            cache.set(next)
            return Outcome.Ok(next)
        }
    }

    @Test fun items_hide_trashed_and_put_open_first() = runTest {
        val vm = TodosViewModel(load, cache, mutate, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), settingsStore)
        vm.refresh()
        // all-lists filter: open before done, higher priority before lower
        assertEquals(listOf("Open one", "Work item", "Done one"), vm.state.value.items.map { it.title })
    }

    @Test fun active_list_filter_restricts_items() = runTest {
        val vm = TodosViewModel(load, cache, mutate, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), settingsStore)
        vm.refresh()
        vm.setActiveList("l2")
        assertEquals(listOf("Work item"), vm.state.value.items.map { it.title })
    }

    @Test fun toggleDone_flips_and_reflows() = runTest {
        val vm = TodosViewModel(load, cache, mutate, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), settingsStore)
        vm.refresh()
        vm.toggleDone("t2")
        assertEquals(true, vm.todoById("t2")?.done)
    }

    @Test fun addTodo_appends_and_shows() = runTest {
        val vm = TodosViewModel(load, cache, mutate, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), settingsStore)
        vm.refresh()
        vm.addTodo("Fresh", "l1", "normal", "", "", "", emptyList())
        assertEquals(true, vm.state.value.items.any { it.title == "Fresh" })
    }

    @Test fun deleteList_orphans_todos_and_clears_filter() = runTest {
        val vm = TodosViewModel(load, cache, mutate, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), settingsStore)
        vm.refresh()
        vm.setActiveList("l1")
        vm.deleteList("l1")
        assertEquals(null, vm.activeList.value)
        assertEquals(null, vm.todoById("t2")?.listId)
    }

    @Test fun trashCount_and_trash_view_ignore_list_filter() = runTest {
        val vm = TodosViewModel(load, cache, mutate, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), settingsStore)
        vm.refresh()
        assertEquals(1, vm.trashCount.value)
        vm.setActiveList("l2")   // filter that would exclude the l1-scoped trashed item
        vm.setTrash(true)
        assertEquals(listOf("t3"), vm.state.value.items.map { it.id })
    }

    @Test fun setQuery_filters_active_list() = runTest {
        val vm = TodosViewModel(load, cache, mutate, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), settingsStore)
        vm.refresh()
        vm.setQuery("work")
        assertEquals(listOf("Work item"), vm.state.value.items.map { it.title })
        vm.setQuery("")
        assertEquals(listOf("Open one", "Work item", "Done one"), vm.state.value.items.map { it.title })
    }

    @Test fun setQuery_does_not_affect_trash_view() = runTest {
        val vm = TodosViewModel(load, cache, mutate, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), settingsStore)
        vm.refresh()
        vm.setTrash(true)
        vm.setQuery("nomatch")
        assertEquals(listOf("t3"), vm.state.value.items.map { it.id })
    }

    @Test fun allTags_is_sorted_distinct_union_of_non_trashed() = runTest {
        val vm = TodosViewModel(load, cache, mutate, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), settingsStore)
        vm.refresh()
        // "Urgent"/"urgent" collapse case-insensitively (first-seen casing); "secret" trashed → excluded.
        assertEquals(listOf("chore", "Urgent"), vm.allTags.value)
    }

    @Test fun setActiveTag_filters_case_insensitively_and_combines_with_list() = runTest {
        val vm = TodosViewModel(load, cache, mutate, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), settingsStore)
        vm.refresh()
        vm.setActiveTag("urgent")
        assertEquals(listOf("t2", "t4"), vm.state.value.items.map { it.id })
        vm.setActiveList("l2")   // AND with list filter
        assertEquals(listOf("t4"), vm.state.value.items.map { it.id })
    }

    @Test fun activeTag_ignored_in_trash_view() = runTest {
        val vm = TodosViewModel(load, cache, mutate, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), settingsStore)
        vm.refresh()
        vm.setActiveTag("urgent")   // no trashed todo has "urgent"
        vm.setTrash(true)
        assertEquals(listOf("t3"), vm.state.value.items.map { it.id })
    }

    @Test fun restore_deleteForever_and_emptyTrash() = runTest {
        val vm = TodosViewModel(load, cache, mutate, io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), settingsStore)
        vm.refresh()
        vm.restore("t3")
        assertEquals(false, vm.todoById("t3")?.trashed)
        assertEquals(0, vm.trashCount.value)

        vm.trashTodo("t1")
        vm.deleteForever("t1")
        assertEquals(null, vm.todoById("t1"))

        vm.trashTodo("t2")
        vm.emptyTrash()
        assertEquals(null, vm.todoById("t2"))
        assertEquals(true, vm.todoById("t4") != null)
    }
}
