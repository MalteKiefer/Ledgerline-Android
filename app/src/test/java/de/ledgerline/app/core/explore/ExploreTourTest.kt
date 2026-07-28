package de.ledgerline.app.core.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte-near checks for the tour calorie + direction-arrow logic vs the web. */
class ExploreTourTest {

    @Test fun calories_null_without_weight_or_distance() {
        assertNull(ExploreCalories.estimate(distanceM = 5000.0, durationS = 3600.0, ascentM = 0.0, weightKg = 0.0, sex = null))
        assertNull(ExploreCalories.estimate(distanceM = 0.0, durationS = 3600.0, ascentM = 0.0, weightKg = 70.0, sex = null))
    }

    @Test fun calories_flat_walk() {
        // 5 km in 1 h = 5 km/h → MET 5.0; active = (5-1)*70*1 = 280 kcal, +0 vertical.
        assertEquals(280L, ExploreCalories.estimate(5000.0, 3600.0, 0.0, 70.0, null))
    }

    @Test fun calories_female_adjustment_and_climb() {
        // With ascent the vertical term + female factor apply; just assert it's positive + lower than male.
        val male = ExploreCalories.estimate(5000.0, 3600.0, 300.0, 70.0, "m")!!
        val female = ExploreCalories.estimate(5000.0, 3600.0, 300.0, 70.0, "f")!!
        assertTrue(female < male)
        assertTrue(male > 280)
    }

    @Test fun calories_planned_route_estimates_duration() {
        // durationS = 0 → Naismith estimate → still a number.
        assertTrue(ExploreCalories.estimate(5000.0, 0.0, 0.0, 70.0, null)!! > 0)
    }

    @Test fun bearing_cardinal_directions() {
        assertEquals(0.0, TrackArrows.bearing(0.0, 0.0, 1.0, 0.0), 1.0)    // north
        assertEquals(90.0, TrackArrows.bearing(0.0, 0.0, 0.0, 1.0), 1.0)   // east
    }

    @Test fun arrows_empty_for_short_track() {
        val pts = listOf(TrackPoint(0.0, 0.0, t = 0L), TrackPoint(0.0, 0.0001, t = 1L)) // ~11 m < 80 m
        assertTrue(TrackArrows.compute(pts).isEmpty())
    }

    @Test fun arrows_count_scales_with_length() {
        // A straight ~2 km eastward line → round(2000/400)=5 arrows, all pointing east (~90°).
        val pts = (0..20).map { TrackPoint(0.0, it * 0.001, t = it.toLong()) } // each ~111 m east → ~2.2 km total
        val arrows = TrackArrows.compute(pts)
        assertTrue(arrows.size in 4..40)
        arrows.forEach { assertEquals(90.0, it.bearingDeg, 1.0) }
    }
}
