package de.ledgerline.app.core.backup

import android.content.ContentResolver
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.offline.ConstraintChecker
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.SettingsStore
import de.ledgerline.app.data.backup.BackupItem
import de.ledgerline.app.data.backup.BackupScanner
import de.ledgerline.app.data.backup.BackupStateStore
import de.ledgerline.app.domain.model.Session
import de.ledgerline.app.domain.usecase.ImportPhotos
import de.ledgerline.app.domain.usecase.ImportResult
import de.ledgerline.app.domain.usecase.PhotoSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryBackupManagerTest {
    private val session = Session("https://h", "tok", "", null)

    private fun item(id: Long) =
        BackupItem(id, mockk(relaxed = true), "IMG_$id.jpg", "image/jpeg", 100, id)

    private fun manager(
        scanner: BackupScanner,
        importPhotos: ImportPhotos,
        state: BackupStateStore,
        vk: ByteArray? = ByteArray(32),
        enabled: Boolean = true,
        albums: Set<String> = setOf("b1"),
        wifiOk: Boolean = true,
        deleteAfter: Boolean = false,
    ): GalleryBackupManager {
        val settings = mockk<SettingsStore>()
        every { settings.backupEnabled } returns kotlinx.coroutines.flow.flowOf(enabled)
        every { settings.backupAlbumIds } returns kotlinx.coroutines.flow.flowOf(albums)
        every { settings.backupDeleteAfter } returns kotlinx.coroutines.flow.flowOf(deleteAfter)
        every { settings.prefetchWifiOnly } returns kotlinx.coroutines.flow.flowOf(true)
        every { settings.prefetchChargingOnly } returns kotlinx.coroutines.flow.flowOf(false)
        val sessions = mockk<SessionHolder>(); every { sessions.get() } returns session
        val vkHolder = mockk<VaultKeyHolder>(); every { vkHolder.get() } returns vk
        val constraints = mockk<ConstraintChecker>()
        every { constraints.wifiConstraintMet(any()) } returns wifiOk
        every { constraints.chargingConstraintMet(any()) } returns true
        val ops = mockk<OperationManager>(relaxed = true)
        coEvery { ops.run(any(), any(), any()) } answers {
            val block = thirdArg<suspend ((Int, Int) -> Unit) -> Unit>()
            kotlinx.coroutines.runBlocking { block { _, _ -> } }
            mockk(relaxed = true)
        }
        val resolver = mockk<ContentResolver>(relaxed = true)
        return GalleryBackupManager(
            scanner, importPhotos, state, settings, sessions, vkHolder, constraints, ops, resolver,
            ioDispatcher = Dispatchers.Unconfined,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @Test fun `locked vault does nothing`() = runTest {
        val importPhotos = mockk<ImportPhotos>(relaxed = true)
        val scanner = mockk<BackupScanner>(); every { scanner.scan(any()) } returns listOf(item(1))
        val state = mockk<BackupStateStore>(relaxed = true)
        manager(scanner, importPhotos, state, vk = null).runNow()
        coVerify(exactly = 0) { importPhotos.invoke(any(), any()) }
    }

    @Test fun `disabled does nothing`() = runTest {
        val importPhotos = mockk<ImportPhotos>(relaxed = true)
        val scanner = mockk<BackupScanner>(); every { scanner.scan(any()) } returns listOf(item(1))
        val state = mockk<BackupStateStore>(relaxed = true)
        manager(scanner, importPhotos, state, enabled = false).runNow()
        coVerify(exactly = 0) { importPhotos.invoke(any(), any()) }
    }

    @Test fun `constraint blocked does nothing`() = runTest {
        val importPhotos = mockk<ImportPhotos>(relaxed = true)
        val scanner = mockk<BackupScanner>(); every { scanner.scan(any()) } returns listOf(item(1))
        val state = mockk<BackupStateStore>(relaxed = true)
        manager(scanner, importPhotos, state, wifiOk = false).runNow()
        coVerify(exactly = 0) { importPhotos.invoke(any(), any()) }
    }

    @Test fun `uploads only unknown items and marks them`() = runTest {
        val scanner = mockk<BackupScanner>(); every { scanner.scan(any()) } returns listOf(item(1), item(2))
        val state = mockk<BackupStateStore>(relaxed = true)
        coEvery { state.backedUpIds() } returns setOf(1L)
        val markSlot = slot<Set<Long>>()
        coEvery { state.mark(capture(markSlot)) } returns Unit
        val importPhotos = mockk<ImportPhotos>()
        val srcSlot = slot<List<PhotoSource>>()
        coEvery { importPhotos.invoke(capture(srcSlot), any()) } returns ImportResult(done = 1, failed = 0)

        manager(scanner, importPhotos, state).runNow()

        assertEquals(1, srcSlot.captured.size)
        assertEquals(setOf(2L), markSlot.captured)
    }

    @Test fun `only succeeded items are marked, failed one retried`() = runTest {
        val scanner = mockk<BackupScanner>(); every { scanner.scan(any()) } returns listOf(item(1), item(2))
        val state = mockk<BackupStateStore>(relaxed = true)
        coEvery { state.backedUpIds() } returns emptySet()
        val markSlot = slot<Set<Long>>()
        coEvery { state.mark(capture(markSlot)) } returns Unit
        val importPhotos = mockk<ImportPhotos>()
        val srcSlot = slot<List<PhotoSource>>()
        // Item #2's source fails → it must NOT be marked (retried next run); #1 is marked.
        coEvery { importPhotos.invoke(capture(srcSlot), any()) } answers {
            val srcs = firstArg<List<PhotoSource>>()
            ImportResult(done = 2, failed = 1, failedSources = listOf(srcs[1]))
        }

        manager(scanner, importPhotos, state).runNow()

        assertEquals(setOf(1L), markSlot.captured)
    }

    @Test fun `deleteAfter enqueues succeeded originals`() = runTest {
        val scanner = mockk<BackupScanner>(); every { scanner.scan(any()) } returns listOf(item(1))
        val state = mockk<BackupStateStore>(relaxed = true)
        coEvery { state.backedUpIds() } returns emptySet()
        val importPhotos = mockk<ImportPhotos>()
        coEvery { importPhotos.invoke(any(), any()) } returns ImportResult(done = 1, failed = 0)

        manager(scanner, importPhotos, state, deleteAfter = true).runNow()

        coVerify(exactly = 1) { state.enqueueDelete(any()) }
    }

    @Test fun `deleteAfter off never enqueues`() = runTest {
        val scanner = mockk<BackupScanner>(); every { scanner.scan(any()) } returns listOf(item(1))
        val state = mockk<BackupStateStore>(relaxed = true)
        coEvery { state.backedUpIds() } returns emptySet()
        val importPhotos = mockk<ImportPhotos>()
        coEvery { importPhotos.invoke(any(), any()) } returns ImportResult(done = 1, failed = 0)

        manager(scanner, importPhotos, state, deleteAfter = false).runNow()

        coVerify(exactly = 0) { state.enqueueDelete(any()) }
    }
}
