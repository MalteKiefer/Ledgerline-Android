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
import org.junit.Assert.assertNull
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
                Note(id = "a", title = "Alpha", updated = "2026-01-01T00:00:00Z", tags = listOf("Work", "urgent")),
                Note(id = "b", title = "Beta", pinned = true, updated = "2026-01-02T00:00:00Z", tags = listOf("home")),
                Note(id = "c", title = "Gone", trashed = true, tags = listOf("secret")),
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

    @Test fun newBlankNote_is_not_persisted_until_saved() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        val blank = vm.newBlankNote()
        assertEquals("", blank.title)
        assertNotNull(blank.updated)
        // Not in the cache yet (creation persists only on first saveNote).
        assertNull(vm.noteById(blank.id))
        // A blank save is discarded (no empty notes created).
        vm.saveNote(blank.id, "", "", emptyList())
        assertNull(vm.noteById(blank.id))
    }

    @Test fun saveNote_appends_a_new_note_then_updates_it() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        val blank = vm.newBlankNote()
        vm.saveNote(blank.id, "Fresh", "body", emptyList())   // upsert → append
        assertEquals("Fresh", vm.noteById(blank.id)?.title)
        assertTrue(vm.state.value.notes.any { it.id == blank.id })
        vm.saveNote(blank.id, "Fresh edited", "body2", emptyList())  // upsert → update
        assertEquals("Fresh edited", vm.noteById(blank.id)?.title)
    }

    @Test fun saveNote_updates_existing_and_reflows_to_top() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        // Update the older, unpinned note → newest updated, so it sorts above Alpha (but below the pinned Beta).
        vm.saveNote("a", "Alpha edited", "body", emptyList())
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

    @Test fun trashCount_reflects_trashed_notes() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        assertEquals(1, vm.trashCount.value)
        vm.trashNote("a")
        assertEquals(2, vm.trashCount.value)
    }

    @Test fun trash_view_shows_only_trashed() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        vm.setTrash(true)
        assertEquals(listOf("c"), vm.state.value.notes.map { it.id })
    }

    @Test fun restore_moves_item_back_to_active_view() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        vm.setTrash(true)
        vm.restore("c")
        assertTrue(vm.state.value.notes.isEmpty())    // trash now empty
        vm.setTrash(false)
        assertTrue(vm.state.value.notes.any { it.id == "c" })
    }

    @Test fun deleteForever_removes_the_item_entirely() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        vm.deleteForever("c")
        assertNull(vm.noteById("c"))
        assertEquals(0, vm.trashCount.value)
    }

    @Test fun setQuery_filters_active_list() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        vm.setQuery("alph")
        assertEquals(listOf("Alpha"), vm.state.value.notes.map { it.title })
        vm.setQuery("")
        assertEquals(listOf("Beta", "Alpha"), vm.state.value.notes.map { it.title })
    }

    @Test fun setQuery_does_not_affect_trash_view() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        vm.setTrash(true)
        vm.setQuery("nomatch")
        // Trash view ignores the query: the trashed note is still shown.
        assertEquals(listOf("c"), vm.state.value.notes.map { it.id })
    }

    @Test fun allTags_is_sorted_distinct_union_of_non_trashed() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        // "secret" (trashed) excluded; sorted case-insensitively.
        assertEquals(listOf("home", "urgent", "Work"), vm.allTags.value)
    }

    @Test fun setActiveTag_filters_case_insensitively() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        vm.setActiveTag("work")   // exact match, case-insensitive
        assertEquals(listOf("Alpha"), vm.state.value.notes.map { it.title })
        vm.setActiveTag(null)
        assertEquals(listOf("Beta", "Alpha"), vm.state.value.notes.map { it.title })
    }

    @Test fun activeTag_combines_with_query() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        vm.setActiveTag("urgent")
        vm.setQuery("beta")   // Beta has no "urgent" tag → AND yields nothing
        assertTrue(vm.state.value.notes.isEmpty())
        vm.setQuery("alpha")
        assertEquals(listOf("Alpha"), vm.state.value.notes.map { it.title })
    }

    @Test fun activeTag_ignored_in_trash_view() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        vm.setActiveTag("work")   // no trashed note has "work"
        vm.setTrash(true)
        assertEquals(listOf("c"), vm.state.value.notes.map { it.id })
    }

    @Test fun emptyTrash_drops_all_trashed_keeps_live() = runTest {
        val vm = NotesViewModel(load, cache, mutate)
        vm.refresh()
        vm.trashNote("a")
        vm.emptyTrash()
        assertNull(vm.noteById("a"))
        assertNull(vm.noteById("c"))
        assertNotNull(vm.noteById("b"))
    }
}
