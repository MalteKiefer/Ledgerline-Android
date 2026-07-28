package de.ledgerline.app.core.explore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPointFilterTest {
    private val prev = TrackPoint(47.2690, 11.4041, null, 0)

    @Test fun rejects_poor_accuracy() {
        assertFalse(TrackPointFilter.accept(prev, 47.2700, 11.4065, 120.0))
    }

    @Test fun rejects_negative_accuracy() {
        assertFalse(TrackPointFilter.accept(prev, 47.2700, 11.4065, -1.0))
    }

    @Test fun rejects_zero_move_duplicate() {
        // Same location as prev → moved ~0 m, below the 1 m gate.
        assertFalse(TrackPointFilter.accept(prev, 47.2690, 11.4041, 8.0))
    }

    @Test fun accepts_first_point_when_prev_null() {
        assertTrue(TrackPointFilter.accept(null, 47.2690, 11.4041, 8.0))
    }

    @Test fun rejects_first_point_with_bad_accuracy() {
        assertFalse(TrackPointFilter.accept(null, 47.2690, 11.4041, 60.0))
    }

    @Test fun accepts_good_fix_that_moved() {
        assertTrue(TrackPointFilter.accept(prev, 47.2700, 11.4065, 8.0))
    }
}
