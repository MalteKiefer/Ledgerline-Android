package de.ledgerline.app.ui.workspace.files

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.data.UploadedBlob
import de.ledgerline.app.domain.model.*
import de.ledgerline.app.domain.usecase.FileBlobs
import de.ledgerline.app.domain.usecase.FilesUsage
import de.ledgerline.app.domain.usecase.LoadWorkspace
import de.ledgerline.app.domain.usecase.MutateWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream

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

    // Fake mutate: applies the mutation onto the current cached manifest and re-sets the
    // cache (mirrors how MutateWorkspaceImpl → WorkspaceRepository.save updates the cache),
    // then returns Ok so the cache-flow collector recomputes the list.
    private val mutate = object : MutateWorkspace {
        override suspend fun invoke(mutate: (WorkspaceManifest) -> WorkspaceManifest): Outcome<Workspace> {
            val current = cache.value.value ?: Workspace(WorkspaceManifest(), 0)
            val next = Workspace(mutate(current.manifest), current.version + 1)
            cache.set(next)
            return Outcome.Ok(next)
        }
    }

    // Stub blob repo: records deletions/uploads; never touches the network.
    private val deleted = mutableListOf<String>()
    private val blobs = object : FileBlobs {
        override suspend fun upload(name: String, mime: String, size: Long, openInput: () -> InputStream) =
            Outcome.Ok(UploadedBlob(id = "blob-$name", encFileKey = "key-$name", size = size))
        override suspend fun downloadToBytes(blob: String, encFileKey: String) =
            Outcome.Ok(byteArrayOf(1, 2, 3))
        override suspend fun downloadTo(blob: String, encFileKey: String, write: (ByteArray) -> Unit): Outcome<Unit> {
            write(byteArrayOf(1, 2, 3))
            return Outcome.Ok(Unit)
        }
        override suspend fun deleteBlobs(blobs: List<String>) { deleted += blobs }
    }

    // Stub usage: returns a fixed used/quota.
    private val usage = object : FilesUsage {
        override suspend fun invoke(): Pair<Long, Long> = 1024L to 10240L
    }

    private fun vm() = FilesViewModel(load, cache, mutate, blobs, usage)

    @Test fun root_shows_folders_then_files_excluding_trashed() = runTest {
        val vm = vm()
        vm.refresh()
        val ui = vm.state.value
        assertEquals(listOf("Docs"), ui.folders.map { it.name })
        assertEquals(listOf("root.txt"), ui.files.map { it.name })   // f3 trashed hidden, f2 in subfolder
    }

    @Test fun entering_a_folder_shows_its_files() = runTest {
        val vm = vm()
        vm.refresh()
        vm.open("d1")
        assertEquals(listOf("in-docs.txt"), vm.state.value.files.map { it.name })
        vm.back()
        assertEquals(listOf("root.txt"), vm.state.value.files.map { it.name })
    }

    @Test fun createFolder_adds_folder() = runTest {
        val vm = vm()
        vm.refresh()
        vm.createFolder("New")
        assertTrue("New".let { name -> vm.state.value.folders.any { it.name == name } })
    }
}
