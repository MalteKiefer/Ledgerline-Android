package de.ledgerline.app.ui.workspace.todos

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.*
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.Dispatchers
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

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun ws() = Workspace(
        WorkspaceManifest(
            todoLists = listOf(TodoList(id = "l1", name = "Home")),
            todos = listOf(
                TodoItem(id = "t1", listId = "l1", title = "Done one", done = true),
                TodoItem(id = "t2", listId = "l1", title = "Open one", done = false),
                TodoItem(id = "t3", listId = "l1", title = "Gone", trashed = true),
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

    @Test fun sections_hide_trashed_and_put_open_first() = runTest {
        val vm = TodosViewModel(load, cache)
        vm.refresh()
        val s = vm.state.value.sections
        assertEquals(listOf("Home"), s.map { it.listName })
        assertEquals(listOf("Open one", "Done one"), s[0].items.map { it.title })
    }
}
