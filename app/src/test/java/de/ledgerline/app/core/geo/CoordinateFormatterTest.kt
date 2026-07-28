package de.ledgerline.app.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Expected strings/values taken from iOS `CoordinateFormatterTests.swift`. */
class CoordinateFormatterTest {
    // Washington Monument — a standard geodesy reference point.
    private val lat = 38.8895
    private val lng = -77.0353

    @Test fun decimal_degrees() {
        assertEquals("38.889500, -77.035300", CoordinateFormatter.format(lat, lng, CoordinateFormat.DD))
    }
    @Test fun degrees_decimal_minutes() {
        assertEquals("38°53.370′ N, 77°02.118′ W", CoordinateFormatter.format(lat, lng, CoordinateFormat.DDM))
    }
    @Test fun degrees_minutes_seconds() {
        assertEquals("38°53′22.2″ N, 77°02′07.1″ W", CoordinateFormatter.format(lat, lng, CoordinateFormat.DMS))
    }

    @Test fun dms_carry_seconds() {
        assertEquals("10°10′00.0″ N, 0°00′00.0″ E", CoordinateFormatter.format(10.166666, 0.0, CoordinateFormat.DMS))
    }
    @Test fun ddm_carry_minutes() {
        assertEquals("10°10.000′ N, 0°00.000′ E", CoordinateFormatter.format(10.166666, 0.0, CoordinateFormat.DDM))
    }
    @Test fun dms_carry_into_degrees() {
        assertEquals("39°00′00.0″ N, 0°00′00.0″ E", CoordinateFormatter.format(38.999999, 0.0, CoordinateFormat.DMS))
    }

    @Test fun utm_out_of_range_returns_dd() {
        val dd = CoordinateFormatter.format(86.0, 10.0, CoordinateFormat.DD)
        assertEquals(dd, CoordinateFormatter.format(86.0, 10.0, CoordinateFormat.UTM))
    }
    @Test fun mgrs_out_of_range_returns_dd() {
        val dd = CoordinateFormatter.format(86.0, 10.0, CoordinateFormat.DD)
        assertEquals(dd, CoordinateFormatter.format(86.0, 10.0, CoordinateFormat.MGRS))
    }

    // Washington Monument → 18S 323478 4306483 ; MGRS 18S UJ 23478 06483
    @Test fun utm_washington_monument() {
        val s = CoordinateFormatter.format(lat, lng, CoordinateFormat.UTM)
        assertTrue("UTM prefix wrong: $s", s.startsWith("18S "))
        val parts = s.split(" ")
        assertEquals(3, parts.size)
        assertTrue(abs(parts[1].toInt() - 323_478) <= 2)
        assertTrue(abs(parts[2].toInt() - 4_306_483) <= 2)
    }
    @Test fun mgrs_washington_monument() {
        val s = CoordinateFormatter.format(lat, lng, CoordinateFormat.MGRS)
        assertTrue("MGRS prefix wrong: $s", s.startsWith("18S UJ "))
        val parts = s.split(" ")
        assertEquals(4, parts.size)
        assertTrue(abs(parts[2].toInt() - 23_478) <= 2)
        assertTrue(abs(parts[3].toInt() - 6_483) <= 2)
    }

    // Sydney Opera House (−33.8568°, 151.2153°) — southern hemisphere.
    // → 56H 334901 6252289 ; MGRS 56H LH 34900 52288
    @Test fun utm_sydney_opera_house() {
        val s = CoordinateFormatter.format(-33.8568, 151.2153, CoordinateFormat.UTM)
        assertTrue("UTM prefix wrong: $s", s.startsWith("56H "))
        val parts = s.split(" ")
        assertEquals(3, parts.size)
        assertTrue(abs(parts[1].toInt() - 334_901) <= 2)
        assertTrue(abs(parts[2].toInt() - 6_252_289) <= 2)
    }
    @Test fun mgrs_sydney_opera_house() {
        val s = CoordinateFormatter.format(-33.8568, 151.2153, CoordinateFormat.MGRS)
        assertTrue("MGRS prefix wrong: $s", s.startsWith("56H LH "))
        val parts = s.split(" ")
        assertEquals(4, parts.size)
        assertTrue(abs(parts[2].toInt() - 34_900) <= 2)
        assertTrue(abs(parts[3].toInt() - 52_288) <= 2)
    }
}
