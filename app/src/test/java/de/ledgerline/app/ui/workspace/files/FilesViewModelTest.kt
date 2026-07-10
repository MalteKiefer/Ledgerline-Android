package de.ledgerline.app.ui.workspace.files

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

class FilesViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun ws() = Workspace(
        WorkspaceManifest(
            files = listOf(
                FileEntry(id = "f1", name = "root.txt", size = 10, folder = null),
                FileEntry(id = "f2", name = "in-docs.txt", size = 20, folder = "d1"),
                FileEntry(id = "f3", name = "gone.txt", size = 5, folder = null, trashed = true),
            ),
            fileFolders = listOf(NamedFolder(id = "d1", name = "Docs", parent = null)),
        ),
        version = 3,
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

    @Test fun root_shows_folders_then_files_excluding_trashed() = runTest {
        val vm = FilesViewModel(load, cache)
        vm.refresh()
        val ui = vm.state.value
        assertEquals(listOf("Docs"), ui.folders.map { it.name })
        assertEquals(listOf("root.txt"), ui.files.map { it.name })   // f3 trashed hidden, f2 in subfolder
    }

    @Test fun entering_a_folder_shows_its_files() = runTest {
        val vm = FilesViewModel(load, cache)
        vm.refresh()
        vm.open("d1")
        assertEquals(listOf("in-docs.txt"), vm.state.value.files.map { it.name })
        vm.back()
        assertEquals(listOf("root.txt"), vm.state.value.files.map { it.name })
    }
}
