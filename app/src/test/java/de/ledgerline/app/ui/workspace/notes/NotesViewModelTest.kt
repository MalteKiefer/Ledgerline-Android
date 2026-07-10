package de.ledgerline.app.ui.workspace.notes

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest
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

class NotesViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun ws() = Workspace(
        WorkspaceManifest(
            notes = listOf(
                Note(id = "a", title = "Alpha", updated = "2026-01-01T00:00:00Z"),
                Note(id = "b", title = "Beta", pinned = true, updated = "2026-01-02T00:00:00Z"),
                Note(id = "c", title = "Gone", trashed = true),
            )
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

    @Test fun pinned_first_trashed_hidden() = runTest {
        val vm = NotesViewModel(load, cache)
        vm.refresh()
        assertEquals(listOf("Beta", "Alpha"), vm.state.value.notes.map { it.title })
    }
}
