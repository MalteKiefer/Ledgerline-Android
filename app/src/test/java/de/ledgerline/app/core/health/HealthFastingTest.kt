package de.ledgerline.app.core.health

import de.ledgerline.app.domain.model.HealthFast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Byte-parity checks for the pure fasting logic vs the web `health-fasting.js`. */
class HealthFastingTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun activeFast_is_the_open_one() {
        val a = HealthFast("a", "2026-07-27T08:00:00Z", end = "2026-07-27T20:00:00Z", targetHours = 12)
        val b = HealthFast("b", "2026-07-27T21:00:00Z", end = null, targetHours = 16)
        assertEquals("b", HealthFasting.activeFast(listOf(a, b))?.id)
        assertNull(HealthFasting.activeFast(listOf(a)))
    }

    @Test fun normalizeFasts_keeps_earliest_active_voids_rest() {
        val a = HealthFast("a", "2026-07-27T10:00:00Z", end = null, targetHours = 16)
        val b = HealthFast("b", "2026-07-27T09:00:00Z", end = null, targetHours = 16) // earlier → stays active
        val (out, changed) = HealthFasting.normalizeFasts(listOf(a, b))
        assertTrue(changed)
        assertEquals(1, out.count { it.end.isNullOrEmpty() })
        assertEquals("b", HealthFasting.activeFast(out)?.id)
        // The voided one is closed at its own start (zero-length), not deleted.
        assertEquals("2026-07-27T10:00:00Z", out.first { it.id == "a" }.end)
    }

    @Test fun normalizeFasts_noop_when_single_active() {
        val a = HealthFast("a", "2026-07-27T10:00:00Z", end = null, targetHours = 16)
        assertFalse(HealthFasting.normalizeFasts(listOf(a)).second)
    }

    @Test fun progress_and_formatting() {
        val f = HealthFast("a", "2026-07-27T10:00:00Z", end = null, targetHours = 16)
        val now = ms("2026-07-27T11:24:00Z") // 1h24m = 5040s
        val p = HealthFasting.progress(f, now)
        assertEquals(5040L, p.elapsed)
        assertEquals(16 * 3600L, p.target)
        assertFalse(p.reached)
        assertEquals("1h 24m", HealthFasting.formatDuration(p.elapsed))
        assertEquals("01:24:00", HealthFasting.formatDurationHMS(p.elapsed))
        assertEquals(9, HealthFasting.pct(f, now)) // 5040 / 57600 = 8.75% → round → 9
    }

    @Test fun template_label() {
        assertEquals("16:8", HealthFasting.templateLabel(16))
        assertEquals("13:11", HealthFasting.templateLabel(13))
        assertEquals("", HealthFasting.templateLabel(null))
        assertEquals("", HealthFasting.templateLabel(0))
    }

    @Test fun isValid_rules() {
        assertTrue(HealthFasting.isValid("2026-07-27T10:00:00Z", null, 16))
        assertTrue(HealthFasting.isValid("2026-07-27T10:00:00Z", "2026-07-27T20:00:00Z", 16))
        assertFalse(HealthFasting.isValid("2026-07-27T10:00:00Z", "2026-07-27T09:00:00Z", 16)) // end before start
        assertFalse(HealthFasting.isValid(null, null, 16))
        assertFalse(HealthFasting.isValid("2026-07-27T10:00:00Z", null, 0))
        assertFalse(HealthFasting.isValid("2026-07-27T10:00:00Z", null, 49))
    }
}
