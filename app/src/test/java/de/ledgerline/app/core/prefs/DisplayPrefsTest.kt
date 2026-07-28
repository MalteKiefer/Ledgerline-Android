package de.ledgerline.app.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayPrefsTest {

    @Test fun defaults_are_metric_24h() {
        val p = DisplayPrefs()
        assertEquals("km", p.distanceUnitLabel())
        assertEquals("m", p.elevationUnitLabel())
        assertEquals(false, p.is12h)
        assertEquals(false, p.imperialDistance)
    }

    @Test fun conversions_match_web() {
        val mi = DisplayPrefs(distance = "mi", elevation = "ft")
        assertEquals(6.21, mi.distanceValue(10_000.0), 1e-9)  // 10 km = 6.21 mi (2 dp)
        assertEquals(3281.0, mi.elevationValue(1000.0), 1e-9) // 1000 m = 3281 ft (0 dp)
        val km = DisplayPrefs()
        assertEquals(10.0, km.distanceValue(10_000.0), 1e-9)
        assertEquals(820.0, km.elevationValue(820.0), 1e-9)
    }

    @Test fun healthUnits_mapping() {
        val p = DisplayPrefs(weight = "lb", temp = "f", glucose = "mmoll")
        val u = p.healthUnits()
        assertEquals("lb", u.weight)
        assertEquals("f", u.temp)
        assertEquals("mmoll", u.glucose)
    }

    @Test fun fromMap_and_toMap_roundtrip() {
        val map = mapOf("distance" to "mi", "elevation" to "ft", "weight" to "lb", "temp" to "f", "glucose" to "mmoll", "time_format" to "12h")
        val p = DisplayPrefs.fromMap(map)
        assertEquals("mi", p.distance)
        assertEquals("12h", p.timeFormat)
        assertEquals(true, p.is12h)
        assertEquals(map, p.toMap())
    }
}
