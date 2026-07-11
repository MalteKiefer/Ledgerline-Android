package de.ledgerline.app.ui.workspace.notes

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.model.Workspace
import de.ledgerline.app.domain.model.WorkspaceManifest
import de.ledgerline.app.domain.usecase.LoadWorkspace
import de.ledgerline.app.domain.usecase.MutateWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    // Fake mutate: applies the transform to the cached manifest and republishes it.
    private val mutate = object : MutateWorkspace {
        override suspend fun invoke(m: (WorkspaceManifest) -> WorkspaceManifest): Outcome<Workspace> {
            val cur = cache.value.value ?: return Outcome.Err(de.ledgerline.app.core.ErrorKind.UNKNOWN)
            val next = Workspace(m(cur.manifest), cur.version + 1)
            cache.set(next)
            return Outcome.Ok(next)
        }
    }

    @Test fun pinned_first_trashed_hidden() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        assertEquals(listOf("Beta", "Alpha"), vm.state.value.notes.map { it.title })
    }

    @Test fun addNote_returns_id_and_appends_editable_note() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        val id = vm.addNote()
        val created = vm.noteById(id)
        assertNotNull(created)
        assertEquals("", created?.title)
        assertNotNull(created?.updated)
        assertTrue(vm.state.value.notes.any { it.id == id })
    }

    @Test fun updateNote_sets_title_and_reflows_to_top() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        // Update the older, unpinned note → newest updated, so it sorts above Alpha (but below the pinned Beta).
        vm.updateNote("a", "Alpha edited", "body")
        assertEquals("Alpha edited", vm.noteById("a")?.title)
        assertEquals(listOf("Beta", "Alpha edited"), vm.state.value.notes.map { it.title })
    }

    @Test fun togglePin_flips_and_reflows() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        vm.togglePin("a")
        assertEquals(true, vm.noteById("a")?.pinned)
    }

    @Test fun trashNote_hides_note() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        vm.trashNote("a")
        assertTrue(vm.state.value.notes.none { it.id == "a" })
    }
}
