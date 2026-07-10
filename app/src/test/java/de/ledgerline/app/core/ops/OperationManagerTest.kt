package de.ledgerline.app.core.ops

import de.ledgerline.app.core.security.VaultLocker
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OperationManagerTest {

    private class FakeSetting(enabled: Boolean) : BackgroundOpsSetting {
        override val enabledFlow = MutableStateFlow(enabled)
    }

    private class RecordingServiceController : ServiceController {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        override fun start() { starts.incrementAndGet() }
        override fun stop() { stops.incrementAndGet() }
    }

    private fun manager(
        setting: BackgroundOpsSetting,
        service: ServiceController,
        locker: VaultLocker = mockk(relaxed = true),
    ) = OperationManager(setting, locker, service)

    @Test
    fun run_adds_progress_removes_on_completion() = runTest {
        val service = RecordingServiceController()
        val mgr = manager(FakeSetting(true), service)
        val gate = CompletableDeferred<Unit>()
        val reported = CompletableDeferred<Unit>()

        val job = mgr.run(OpKind.FACE_SCAN, total = 10) { report ->
            report(3, 10)
            reported.complete(Unit)
            gate.await()
        }

        // Op is active once the reporter has fired.
        reported.await()
        assertTrue(mgr.hasActive())
        val active = mgr.active.value
        assertEquals(1, active.size)
        assertEquals(OpKind.FACE_SCAN, active[0].kind)
        assertEquals(3, active[0].current)
        assertEquals(10, active[0].total)

        gate.complete(Unit)
        job.join()

        assertFalse(mgr.hasActive())
        assertTrue(mgr.active.value.isEmpty())
    }

    @Test
    fun service_started_on_first_op_and_stopped_on_last_when_enabled() = runTest {
        val service = RecordingServiceController()
        val mgr = manager(FakeSetting(true), service)
        val g1 = CompletableDeferred<Unit>()
        val g2 = CompletableDeferred<Unit>()

        val j1 = mgr.run(OpKind.UPLOAD) { g1.await() }
        assertEquals(1, service.starts.get())

        // Second op does NOT restart the service (still non-empty).
        val j2 = mgr.run(OpKind.DUPLICATE_SCAN) { g2.await() }
        assertEquals(1, service.starts.get())
        assertEquals(0, service.stops.get())

        g1.complete(Unit); j1.join()
        // Still one op left → not stopped yet.
        assertEquals(0, service.stops.get())

        g2.complete(Unit); j2.join()
        assertEquals(1, service.stops.get())
    }

    @Test
    fun service_not_started_when_disabled() = runTest {
        val service = RecordingServiceController()
        val mgr = manager(FakeSetting(false), service)
        val gate = CompletableDeferred<Unit>()

        val job = mgr.run(OpKind.UPLOAD) { gate.await() }
        assertTrue(mgr.hasActive())
        assertEquals(0, service.starts.get())

        gate.complete(Unit); job.join()
        // stop() is still called on drain (idempotent), but start was never called.
        assertEquals(0, service.starts.get())
    }

    @Test
    fun deferred_lock_only_when_backgrounded_enabled_and_drained() = runTest {
        val service = RecordingServiceController()
        val locker = mockk<VaultLocker>(relaxed = true)
        val mgr = manager(FakeSetting(true), service, locker)
        val gate = CompletableDeferred<Unit>()

        val job = mgr.run(OpKind.FACE_SCAN) { gate.await() }
        mgr.onAppBackground()

        gate.complete(Unit); job.join()

        verify(exactly = 1) { locker.lock() }
    }

    @Test
    fun no_deferred_lock_when_still_foreground() = runTest {
        val service = RecordingServiceController()
        val locker = mockk<VaultLocker>(relaxed = true)
        val mgr = manager(FakeSetting(true), service, locker)
        val gate = CompletableDeferred<Unit>()

        val job = mgr.run(OpKind.FACE_SCAN) { gate.await() }
        // Never backgrounded.
        gate.complete(Unit); job.join()

        verify(exactly = 0) { locker.lock() }
    }

    @Test
    fun no_deferred_lock_when_disabled_even_if_backgrounded() = runTest {
        val service = RecordingServiceController()
        val locker = mockk<VaultLocker>(relaxed = true)
        val mgr = manager(FakeSetting(false), service, locker)
        val gate = CompletableDeferred<Unit>()

        val job = mgr.run(OpKind.FACE_SCAN) { gate.await() }
        mgr.onAppBackground()
        gate.complete(Unit); job.join()

        verify(exactly = 0) { locker.lock() }
    }
}
