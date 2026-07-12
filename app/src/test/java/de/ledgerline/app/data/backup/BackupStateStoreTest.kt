package de.ledgerline.app.data.backup

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupStateStoreTest {
    private fun store() = BackupStateStore(ApplicationProvider.getApplicationContext())

    @After fun tearDown() = runTest { store().clear() }

    @Test fun `unmarked id is not contained`() = runTest {
        assertFalse(store().isBackedUp(42L))
    }

    @Test fun `marked ids persist and are contained`() = runTest {
        val s = store()
        s.mark(setOf(1L, 2L, 3L))
        assertTrue(s.isBackedUp(1L))
        assertTrue(s.isBackedUp(3L))
        assertFalse(s.isBackedUp(4L))
    }

    @Test fun `mark is additive`() = runTest {
        val s = store()
        s.mark(setOf(1L))
        s.mark(setOf(2L))
        assertEquals(setOf(1L, 2L), s.backedUpIds())
    }
}
