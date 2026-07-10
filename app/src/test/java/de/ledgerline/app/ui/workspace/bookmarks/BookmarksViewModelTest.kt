package de.ledgerline.app.ui.workspace.bookmarks

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

class BookmarksViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun ws() = Workspace(
        WorkspaceManifest(
            bookmarks = listOf(
                Bookmark(id = "1", title = "Grouped", url = "https://a.example", folderId = "g1"),
                Bookmark(id = "2", title = "Loose", url = "https://b.example", folderId = null),
                Bookmark(id = "3", title = "Gone", url = "https://c.example", trashed = true),
            ),
            bookmarkFolders = listOf(NamedFolder(id = "g1", name = "Work")),
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

    @Test fun groups_by_folder_with_ungrouped_last_and_hides_trashed() = runTest {
        val vm = BookmarksViewModel(load, cache)
        vm.refresh()
        val groups = vm.state.value.groups
        assertEquals(listOf("Work", null), groups.map { it.folderName })
        assertEquals(listOf("Grouped"), groups[0].bookmarks.map { it.title })
        assertEquals(listOf("Loose"), groups[1].bookmarks.map { it.title })
    }
}
