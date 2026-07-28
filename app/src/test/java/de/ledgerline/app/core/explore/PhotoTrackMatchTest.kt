package de.ledgerline.app.core.explore

import de.ledgerline.app.domain.model.ExploreTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoTrackMatchTest {

    // A short track: two points 0s and 100s apart, ~ (50.0,10.0) → (50.001,10.001).
    private val track = ExploreTrack(
        id = "t1", name = "Tour",
        points = listOf(
            TrackPoint(lat = 50.0, lng = 10.0, ele = 100.0, t = 1_000_000L),
            TrackPoint(lat = 50.001, lng = 10.001, ele = 120.0, t = 1_100_000L),
        ),
    )

    @Test fun interpolates_midway_by_time() {
        val pos = PhotoTrackMatch.interpolatePosition(track.points, 1_050_000L)!!
        assertEquals(50.0005, pos.lat, 1e-9)
        assertEquals(10.0005, pos.lng, 1e-9)
        assertEquals(110.0, pos.ele!!, 1e-9)
    }

    @Test fun interpolate_out_of_span_is_null() {
        assertNull(PhotoTrackMatch.interpolatePosition(track.points, 2_000_000L))
    }

    @Test fun gps_photo_on_track_matches_exif() {
        val m = PhotoTrackMatch.matchPhotoToTracks(50.0, 10.0, 1_000_000L, listOf(track), 3600, 100)
        assertEquals("t1", m.trackId)
        assertEquals("exif", m.source)
        assertEquals(50.0, m.lat!!, 0.0)
    }

    @Test fun gps_photo_far_away_is_exif_but_unassigned() {
        val m = PhotoTrackMatch.matchPhotoToTracks(48.0, 2.0, 1_000_000L, listOf(track), 3600, 100)
        assertNull(m.trackId)
        assertEquals("exif", m.source)
    }

    @Test fun no_gps_photo_in_timespan_interpolates() {
        val m = PhotoTrackMatch.matchPhotoToTracks(null, null, 1_050_000L, listOf(track), 3600, 100)
        assertEquals("t1", m.trackId)
        assertEquals("interpolated", m.source)
        assertEquals(50.0005, m.lat!!, 1e-9)
    }

    @Test fun no_gps_outside_tolerance_is_none() {
        // 2 h after the track ends, tolerance 1 h → no match.
        val m = PhotoTrackMatch.matchPhotoToTracks(null, null, 1_100_000L + 7_200_000L, listOf(track), 3600, 100)
        assertEquals("none", m.source)
        assertNull(m.trackId)
    }

    @Test fun no_data_is_none() {
        assertEquals("none", PhotoTrackMatch.matchPhotoToTracks(null, null, null, listOf(track), 3600, 100).source)
    }
}
