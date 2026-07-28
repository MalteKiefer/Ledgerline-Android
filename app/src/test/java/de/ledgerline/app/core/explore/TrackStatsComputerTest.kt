package de.ledgerline.app.core.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ground-truth values produced by the web `shared/track-parse.js computeStats`
 * over the same hand-built track (run via Node), so this pins byte-exact parity.
 */
class TrackStatsComputerTest {
    private val base = 1_721_600_000_000L
    private val points = listOf(
        TrackPoint(47.2690, 11.4041, 574.0, base + 0),
        TrackPoint(47.2695, 11.4050, 578.0, base + 30_000),
        TrackPoint(47.2700, 11.4065, 583.5, base + 60_000),
        TrackPoint(47.2710, 11.4080, 590.0, base + 90_000),
        TrackPoint(47.2725, 11.4095, 588.0, base + 120_000),
        TrackPoint(47.2735, 11.4110, 595.5, base + 150_000),
    )

    @Test fun computeStats_matches_web() {
        val s = TrackStatsComputer.computeStats(points)
        assertEquals(732.74, s.distanceM, 0.01)
        assertEquals(150.0, s.durationTotalS, 0.001)
        assertEquals(150.0, s.durationMovingS, 0.001)
        assertEquals(21.5, s.ascentM, 0.01)   // ±5 m deadband: 9.5+6.5+5.5
        assertEquals(0.0, s.descentM, 0.01)
        assertEquals(574.0, s.minEleM!!, 1e-9)
        assertEquals(595.5, s.maxEleM!!, 1e-9)
        assertEquals(4.8849, s.avgSpeedMps, 0.0001)
        assertEquals(6.7188, s.maxSpeedMps, 0.0001)
        assertEquals(6, s.pointCount)

        val expectedDist = doubleArrayOf(
            0.0, 87.76321696981306, 213.85847697945587,
            372.5181295790702, 574.0811965708452, 732.7370357768401,
        )
        val expectedEle = doubleArrayOf(574.0, 578.0, 583.5, 590.0, 588.0, 595.5)
        assertEquals(expectedDist.size, s.elevationProfile.size)
        s.elevationProfile.forEachIndexed { i, sample ->
            assertEquals(expectedDist[i], sample.distM, 1e-6)   // profile distM is RAW (unrounded)
            assertEquals(expectedEle[i], sample.eleM!!, 1e-9)
        }
    }

    @Test fun empty_is_all_zero() {
        val s = TrackStatsComputer.computeStats(emptyList())
        assertEquals(0, s.pointCount)
        assertEquals(0.0, s.distanceM, 0.0)
        assertNull(s.minEleM)
        assertNull(s.maxEleM)
        assertEquals(0, s.elevationProfile.size)
    }

    @Test fun smoothedAscentDescent_deadband() {
        // Jitter under 5 m is discarded; a sustained drop of 10 m is committed once.
        val jitter = listOf(
            TrackPoint(0.0, 0.0, 100.0, 0),
            TrackPoint(0.0, 0.0, 103.0, 1),   // +3 held
            TrackPoint(0.0, 0.0, 98.0, 2),    // -2 vs ref 100 held
            TrackPoint(0.0, 0.0, 90.0, 3),    // -10 vs ref 100 → descent 10
        )
        val (asc, desc) = TrackStatsComputer.smoothedAscentDescent(jitter)
        assertEquals(0.0, asc, 1e-9)
        assertEquals(10.0, desc, 1e-9)
    }

    @Test fun maxSpeed_ignores_glitch_over_150mps() {
        val glitch = listOf(
            TrackPoint(0.0, 0.0, null, 0),
            TrackPoint(0.0, 1.0, null, 1_000),   // ~111 km over 1 s → >150 m/s, ignored for max
            TrackPoint(0.0, 1.0001, null, 2_000),
        )
        val s = TrackStatsComputer.computeStats(glitch)
        org.junit.Assert.assertTrue("maxSpeed must drop the glitch", s.maxSpeedMps <= 150.0)
    }

    @Test fun bbox_bounds_track() {
        val b = TrackStatsComputer.bbox(points)!!
        assertEquals(47.2690, b.minLat, 1e-9)
        assertEquals(47.2735, b.maxLat, 1e-9)
        assertEquals(11.4041, b.minLng, 1e-9)
        assertEquals(11.4110, b.maxLng, 1e-9)
    }
}
