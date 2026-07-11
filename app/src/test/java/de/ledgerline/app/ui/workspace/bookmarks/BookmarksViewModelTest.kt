package de.ledgerline.app.ui.workspace.bookmarks

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

class BookmarksViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val settingsStore = mockk<SettingsStore>(relaxed = true) {
        every { linkChooserEnabled } returns flowOf(true)
    }

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun ws() = Workspace(
        WorkspaceManifest(
            bookmarks = listOf(
                Bookmark(id = "1", title = "Beta", url = "https://a.example", folderId = "g1"),
                Bookmark(id = "2", title = "Alpha", url = "https://b.example", folderId = null),
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

    // Fake mutate: applies the transform to the cached manifest and republishes it.
    private val mutate = object : MutateWorkspace {
        override suspend fun invoke(m: (WorkspaceManifest) -> WorkspaceManifest): Outcome<Workspace> {
            val cur = cache.value.value ?: return Outcome.Err(de.ledgerline.app.core.ErrorKind.UNKNOWN)
            val next = Workspace(m(cur.manifest), cur.version + 1)
            cache.set(next)
            return Outcome.Ok(next)
        }
    }

    @Test fun items_hide_trashed_and_sort_by_title() = runTest {
        val vm = BookmarksViewModel(load, cache, mutate, settingsStore)
        vm.refresh()
        assertEquals(listOf("Alpha", "Beta"), vm.state.value.items.map { it.title })
        assertEquals(listOf("Work"), vm.folders.value.map { it.name })
    }

    @Test fun active_folder_filter_restricts_items() = runTest {
        val vm = BookmarksViewModel(load, cache, mutate, settingsStore)
        vm.refresh()
        vm.setActiveFolder("g1")
        assertEquals(listOf("Beta"), vm.state.value.items.map { it.title })
    }

    @Test fun addBookmark_appends_and_shows() = runTest {
        val vm = BookmarksViewModel(load, cache, mutate, settingsStore)
        vm.refresh()
        vm.addBookmark("https://fresh.example", "Fresh", "", null, emptyList())
        assertEquals(true, vm.state.value.items.any { it.title == "Fresh" })
    }

    @Test fun toggleFavorite_flips() = runTest {
        val vm = BookmarksViewModel(load, cache, mutate, settingsStore)
        vm.refresh()
        vm.toggleFavorite("1")
        assertEquals(true, vm.bookmarkById("1")?.favorite)
    }

    @Test fun deleteFolder_orphans_bookmarks_and_clears_filter() = runTest {
        val vm = BookmarksViewModel(load, cache, mutate, settingsStore)
        vm.refresh()
        vm.setActiveFolder("g1")
        vm.deleteFolder("g1")
        assertEquals(null, vm.activeFolder.value)
        assertEquals(null, vm.bookmarkById("1")?.folderId)
    }

    @Test fun trashCount_and_trash_view_ignore_folder_filter() = runTest {
        val vm = BookmarksViewModel(load, cache, mutate, settingsStore)
        vm.refresh()
        assertEquals(1, vm.trashCount.value)
        vm.setActiveFolder("g1")   // filter that would exclude the (folderless) trashed item
        vm.setTrash(true)
        assertEquals(listOf("3"), vm.state.value.items.map { it.id })
    }

    @Test fun restore_deleteForever_and_emptyTrash() = runTest {
        val vm = BookmarksViewModel(load, cache, mutate, settingsStore)
        vm.refresh()
        vm.restore("3")
        assertEquals(false, vm.bookmarkById("3")?.trashed)
        assertEquals(0, vm.trashCount.value)

        vm.trashBookmark("1")
        vm.deleteForever("1")
        assertEquals(null, vm.bookmarkById("1"))

        vm.trashBookmark("2")
        vm.emptyTrash()
        assertEquals(null, vm.bookmarkById("2"))
    }
}
