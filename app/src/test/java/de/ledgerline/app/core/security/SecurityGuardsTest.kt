package de.ledgerline.app.core.security

import de.ledgerline.app.core.crypto.ConstantTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityGuardsTest {

    // ---- UnlockThrottle: escalating, monotonic, never destructive -------------

    @Test fun throttle_no_lock_within_free_attempts() {
        val t = UnlockThrottle()
        repeat(3) { t.recordFailure(now = 0) } // 3 free attempts
        assertEquals(0L, t.remainingLockMs(now = 0))
    }

    @Test fun throttle_locks_and_escalates_after_free_attempts() {
        val t = UnlockThrottle()
        repeat(3) { t.recordFailure(now = 0) }
        t.recordFailure(now = 1_000)                       // 4th → 2s lock (until 3000)
        assertEquals(2_000L, t.remainingLockMs(now = 1_000))
        assertEquals(0L, t.remainingLockMs(now = 3_000))   // expired
        t.recordFailure(now = 3_000)                       // 5th → 4s lock
        assertEquals(4_000L, t.remainingLockMs(now = 3_000))
    }

    @Test fun throttle_caps_at_max_delay() {
        val t = UnlockThrottle()
        repeat(40) { t.recordFailure(now = 0) }            // way past the cap
        assertEquals(300_000L, t.remainingLockMs(now = 0)) // capped at 300s
    }

    @Test fun throttle_success_resets() {
        val t = UnlockThrottle()
        repeat(6) { t.recordFailure(now = 0) }
        t.recordSuccess()
        assertEquals(0L, t.remainingLockMs(now = 0))
    }

    // ---- WipePolicy: always active, only genuine passphrase fails count -------

    @Test fun wipe_policy_default_and_range() {
        assertEquals(10, WipePolicy.effectiveThreshold(0))   // out of range → default
        assertEquals(10, WipePolicy.effectiveThreshold(7))   // not an option → default
        assertEquals(5, WipePolicy.effectiveThreshold(5))    // valid option kept
        assertEquals(3, WipePolicy.effectiveThreshold(3))
    }

    @Test fun wipe_policy_threshold_fires() {
        assertFalse(WipePolicy.shouldWipe(failures = 9, threshold = 10))
        assertTrue(WipePolicy.shouldWipe(failures = 10, threshold = 10))
        assertTrue(WipePolicy.shouldWipe(failures = 11, threshold = 10))
        // A stale "0" resolves to the default, so the feature can never be off.
        assertTrue(WipePolicy.shouldWipe(failures = 10, threshold = 0))
        assertEquals(7, WipePolicy.remaining(failures = 3, threshold = 10))
        assertEquals(0, WipePolicy.remaining(failures = 12, threshold = 10))
    }

    // ---- ClockRollbackGuard: forward-only high-water ---------------------------

    @Test fun clock_rollback_detected_beyond_tolerance() {
        val tol = 5 * 60_000L
        val hw = 1_000_000_000_000L
        assertFalse(ClockRollbackGuard.isRollback(hw + 1, hw, tol))       // forward: ok
        assertFalse(ClockRollbackGuard.isRollback(hw - tol + 1, hw, tol)) // within tolerance: ok
        assertTrue(ClockRollbackGuard.isRollback(hw - tol - 1, hw, tol))  // beyond: rollback
    }

    // ---- ConstantTime: length-checked, difference-accumulating -----------------

    @Test fun constant_time_equal() {
        assertTrue(ConstantTime.equal(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(ConstantTime.equal(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(ConstantTime.equal(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
        assertTrue(ConstantTime.equal(ByteArray(0), ByteArray(0)))
    }
}
