package de.ledgerline.app.core.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Byte-parity checks for the pure metric logic vs the web `health-metrics.js`. */
class HealthMetricsTest {

    @Test fun computeAge_matches_calendar() {
        val now = LocalDate.parse("2026-07-27")
        assertEquals(26, HealthMetrics.computeAge("2000-06-15", now)) // birthday passed this year
        assertEquals(25, HealthMetrics.computeAge("2000-12-31", now)) // birthday later this year
        assertEquals(26, HealthMetrics.computeAge("2000-07-27", now)) // birthday today
        assertNull(HealthMetrics.computeAge("", now))
        assertNull(HealthMetrics.computeAge("not-a-date", now))
    }

    @Test fun computeBmi_one_decimal() {
        assertEquals(22.2, HealthMetrics.computeBmi(72.0, 180.0)!!, 1e-9)
        assertNull(HealthMetrics.computeBmi(0.0, 180.0))
        assertNull(HealthMetrics.computeBmi(72.0, null))
    }

    @Test fun conversions_match_web_rounding() {
        assertEquals(220.5, HealthMetrics.kgToLb(100.0), 1e-9)
        assertEquals(45.4, HealthMetrics.lbToKg(100.0), 1e-9)
        assertEquals(98.6, HealthMetrics.cToF(37.0), 1e-9)
        assertEquals(37.0, HealthMetrics.fToC(98.6), 1e-9)
        assertEquals(5.5, HealthMetrics.mgdlToMmoll(100.0), 1e-9)
        assertEquals(90.0, HealthMetrics.mmollToMgdl(5.0), 1e-9) // 5*18.0182 = 90.091 → 90
    }

    @Test fun classify_thresholds() {
        assertEquals(HealthMetrics.Status.RED, HealthMetrics.classify("spo2", 90.0, null))
        assertEquals(HealthMetrics.Status.AMBER, HealthMetrics.classify("spo2", 93.0, null))
        assertEquals(HealthMetrics.Status.OK, HealthMetrics.classify("spo2", 97.0, null))

        assertEquals(HealthMetrics.Status.RED, HealthMetrics.classify("bp", 145.0, 85.0))
        assertEquals(HealthMetrics.Status.RED, HealthMetrics.classify("bp", 130.0, 95.0))
        assertEquals(HealthMetrics.Status.AMBER, HealthMetrics.classify("bp", 125.0, 70.0))
        assertEquals(HealthMetrics.Status.OK, HealthMetrics.classify("bp", 118.0, 78.0))

        assertEquals(HealthMetrics.Status.OK, HealthMetrics.classify("pulse", 72.0, null))
        assertEquals(HealthMetrics.Status.AMBER, HealthMetrics.classify("pulse", 110.0, null))

        assertEquals(HealthMetrics.Status.AMBER, HealthMetrics.classify("temp", 38.4, null))
        assertEquals(HealthMetrics.Status.RED, HealthMetrics.classify("temp", 39.2, null))
        assertEquals(HealthMetrics.Status.OK, HealthMetrics.classify("temp", 36.6, null))

        assertEquals(HealthMetrics.Status.OK, HealthMetrics.classify("weight", 999.0, null))
        assertEquals(HealthMetrics.Status.OK, HealthMetrics.classify("glucose", 300.0, null))
    }
}
