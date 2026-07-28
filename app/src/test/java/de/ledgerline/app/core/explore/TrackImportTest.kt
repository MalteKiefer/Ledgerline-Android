package de.ledgerline.app.core.explore

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackImportTest {

    @Test fun gpx_trkpt_with_ele_and_time() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <name>Morning Ride</name>
                <trkseg>
                  <trkpt lat="47.269000" lon="11.404100">
                    <ele>574.0</ele>
                    <time>2026-07-24T06:00:00Z</time>
                  </trkpt>
                  <trkpt lat="47.273500" lon="11.411000">
                    <ele>595.5</ele>
                    <time>2026-07-24T06:06:00Z</time>
                  </trkpt>
                  <trkpt lat="47.280000" lon="11.420000">
                    <ele>610.0</ele>
                    <time>2026-07-24T06:12:00Z</time>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val track = TrackImport.parse(gpx, "ride.gpx")
        assertNotNull(track)
        track!!
        assertEquals("gpx", track.sourceFormat)
        assertEquals("Morning Ride", track.name)
        assertEquals(3, track.points.size)

        val first = track.points.first()
        assertEquals(47.269, first.lat, 1e-9)
        assertEquals(11.4041, first.lng, 1e-9)
        assertEquals(574.0, first.ele!!, 1e-9)
        assertEquals(Instant.parse("2026-07-24T06:00:00Z").toEpochMilli(), first.t)

        val last = track.points.last()
        assertEquals(47.28, last.lat, 1e-9)
        assertEquals(11.42, last.lng, 1e-9)
        assertEquals(610.0, last.ele!!, 1e-9)
        assertEquals(Instant.parse("2026-07-24T06:12:00Z").toEpochMilli(), last.t)
    }

    @Test fun gpx_falls_back_to_filename_when_no_name() {
        val gpx = """
            <gpx version="1.1"><trk><trkseg>
              <trkpt lat="10.0" lon="20.0"></trkpt>
              <trkpt lat="10.1" lon="20.1"></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()

        val track = TrackImport.parse(gpx, "/data/tracks/hike-2026.gpx")
        assertNotNull(track)
        assertEquals("hike-2026", track!!.name)
        assertEquals(2, track.points.size)
        // No <ele>/<time> ⇒ null ele, 0L time.
        assertNull(track.points.first().ele)
        assertEquals(0L, track.points.first().t)
    }

    @Test fun kml_linestring_coordinates() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <Placemark>
                  <name>City Loop</name>
                  <LineString>
                    <coordinates>
                      11.404100,47.269000,574
                      11.411000,47.273500,595.5
                      11.420000,47.280000,610
                    </coordinates>
                  </LineString>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()

        val track = TrackImport.parse(kml, "loop.kml")
        assertNotNull(track)
        track!!
        assertEquals("kml", track.sourceFormat)
        assertEquals("City Loop", track.name)
        assertEquals(3, track.points.size)

        val first = track.points.first()
        assertEquals(47.269, first.lat, 1e-9)   // lat is the 2nd field
        assertEquals(11.4041, first.lng, 1e-9)  // lng is the 1st field
        assertEquals(574.0, first.ele!!, 1e-9)
        assertEquals(0L, first.t)               // plain coordinates carry no time

        val last = track.points.last()
        assertEquals(47.28, last.lat, 1e-9)
        assertEquals(11.42, last.lng, 1e-9)
        assertEquals(610.0, last.ele!!, 1e-9)
    }

    @Test fun kml_gx_track_when_and_coord() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2"
                 xmlns:gx="http://www.google.com/kml/ext/2.2">
              <Placemark>
                <name>GX Track</name>
                <gx:Track>
                  <when>2026-07-24T08:00:00Z</when>
                  <when>2026-07-24T08:01:00Z</when>
                  <gx:coord>11.404100 47.269000 574</gx:coord>
                  <gx:coord>11.411000 47.273500 595.5</gx:coord>
                </gx:Track>
              </Placemark>
            </kml>
        """.trimIndent()

        val track = TrackImport.parse(kml, "gx.kml")
        assertNotNull(track)
        track!!
        assertEquals("kml", track.sourceFormat)
        assertEquals(2, track.points.size)

        val first = track.points.first()
        assertEquals(47.269, first.lat, 1e-9)
        assertEquals(11.4041, first.lng, 1e-9)
        assertEquals(574.0, first.ele!!, 1e-9)
        assertEquals(Instant.parse("2026-07-24T08:00:00Z").toEpochMilli(), first.t)
        assertEquals(
            Instant.parse("2026-07-24T08:01:00Z").toEpochMilli(),
            track.points.last().t,
        )
    }

    @Test fun non_xml_returns_null() {
        assertNull(TrackImport.parse("not xml at all", "junk.gpx"))
        assertNull(TrackImport.parse("", "empty.gpx"))
        // Well-formed XML but no track points.
        assertNull(TrackImport.parse("<gpx></gpx>", "nopoints.gpx"))
    }
}
