package de.ledgerline.app.core.units

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected strings taken from iOS `MeasureFormatterTests.swift`. */
class MeasureFormatterTest {
    @Test fun distance_metric() {
        assertEquals("12.34 km", MeasureFormatter.distance(12340.0, UnitSystem.METRIC))
    }
    @Test fun distance_imperial() {
        assertEquals("7.67 mi", MeasureFormatter.distance(12340.0, UnitSystem.IMPERIAL))
    }
    @Test fun elevation_metric() {
        assertEquals("820 m", MeasureFormatter.elevation(820.0, UnitSystem.METRIC))
    }
    @Test fun elevation_imperial() {
        assertEquals("2690 ft", MeasureFormatter.elevation(820.0, UnitSystem.IMPERIAL))
    }
    @Test fun pace_metric() {
        assertEquals("5'33\"/km", MeasureFormatter.pace(3.0, UnitSystem.METRIC))
    }
    @Test fun pace_imperial_and_zero() {
        assertEquals("8'56\"/mi", MeasureFormatter.pace(3.0, UnitSystem.IMPERIAL))
        assertEquals("--", MeasureFormatter.pace(0.0, UnitSystem.METRIC))
    }
    @Test fun speed_metric_and_imperial() {
        assertEquals("18.0 km/h", MeasureFormatter.speed(5.0, UnitSystem.METRIC))
        assertEquals("11.2 mph", MeasureFormatter.speed(5.0, UnitSystem.IMPERIAL))
    }
    @Test fun duration_formats() {
        assertEquals("5:03", MeasureFormatter.duration(303.0))
        assertEquals("0:09", MeasureFormatter.duration(9.0))
        assertEquals("1:24:00", MeasureFormatter.duration(5040.0))
        assertEquals("0:00", MeasureFormatter.duration(-5.0))
    }
}
