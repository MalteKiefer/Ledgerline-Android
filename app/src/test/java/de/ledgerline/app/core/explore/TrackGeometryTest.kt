package de.ledgerline.app.core.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackGeometryTest {
    private val track = listOf(
        TrackPoint(0.0, 0.00, null, 0),
        TrackPoint(0.0, 0.01, null, 1),
        TrackPoint(0.0, 0.02, null, 2),
    )

    @Test fun zero_distance_is_first_point() {
        val c = TrackGeometry.interpolateAtDistance(track, 0.0)!!
        assertEquals(0.0, c.second, 1e-9)
    }

    @Test fun past_end_clamps_to_last_point() {
        val c = TrackGeometry.interpolateAtDistance(track, 1_000_000.0)!!
        assertEquals(0.02, c.second, 1e-6)
    }

    @Test fun monotonic_along_track() {
        val near = TrackGeometry.interpolateAtDistance(track, 300.0)!!
        val far = TrackGeometry.interpolateAtDistance(track, 1500.0)!!
        assertTrue(near.second > 0.0)
        assertTrue(far.second < 0.0201)
        assertTrue(far.second > near.second)
    }

    @Test fun fewer_than_two_points() {
        assertNull(TrackGeometry.interpolateAtDistance(emptyList(), 100.0))
        val one = listOf(TrackPoint(5.0, 6.0, null, 0))
        val c = TrackGeometry.interpolateAtDistance(one, 100.0)!!
        assertEquals(5.0, c.first, 1e-9)
    }

    @Test fun haversine_known_distance() {
        // 0.01° of longitude at the equator with R=6371000 m ≈ 1111.95 m.
        val d = TrackGeometry.haversine(0.0, 0.0, 0.0, 0.01)
        assertEquals(1111.9492664455875, d, 1e-6)
    }
}
