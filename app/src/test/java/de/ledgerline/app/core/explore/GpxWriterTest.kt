package de.ledgerline.app.core.explore

import org.junit.Assert.assertTrue
import org.junit.Test

class GpxWriterTest {
    @Test fun gpx_structure() {
        val points = listOf(
            TrackPoint(47.269, 11.4041, 574.0, 1_721_600_000_000L),
            TrackPoint(47.2735, 11.411, 595.5, 1_721_600_360_000L),
        )
        val gpx = GpxWriter.write("Test & Run", points)

        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("<gpx "))
        assertTrue(gpx.contains("version=\"1.1\""))
        assertTrue(gpx.contains("creator=\"Ledgerline\""))
        assertTrue(gpx.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""))
        assertTrue(gpx.contains("<trkpt lat=\"47.269\" lon=\"11.4041\">"))
        assertTrue(gpx.contains("<ele>574</ele>"))          // integral ele drops ".0"
        assertTrue(gpx.contains("<ele>595.5</ele>"))
        assertTrue(gpx.contains("<time>2024-07-21T22:13:20Z</time>"))
        assertTrue(gpx.contains("Test &amp; Run"))          // XML-escaped name
        assertTrue(gpx.endsWith("</gpx>\n"))
    }

    @Test fun omits_ele_when_null() {
        val gpx = GpxWriter.write("t", listOf(TrackPoint(1.0, 2.0, null, 1_721_599_999_000L)))
        assertTrue(gpx.contains("<trkpt lat=\"1\" lon=\"2\"><time>"))
        assertTrue(!gpx.contains("<ele>"))
    }

    /** A timeless point (0L sentinel — planned routes / GPX-KML without <time>) must NOT emit a
     *  bogus epoch-1970 `<time>`; it is simply omitted. */
    @Test fun omits_time_for_timeless_point() {
        val gpx = GpxWriter.write("t", listOf(TrackPoint(1.0, 2.0, 5.0, 0L)))
        assertTrue(gpx.contains("<trkpt lat=\"1\" lon=\"2\"><ele>5</ele></trkpt>"))
        assertTrue(!gpx.contains("<time>"))
    }
}
