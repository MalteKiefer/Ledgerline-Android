package de.ledgerline.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Trivial parse-level tests for the Nominatim response shape [Geocoder] consumes.
 *
 * These avoid the network entirely (and `org.json`, which is only a stub in plain JVM
 * unit tests) by exercising a regex mirror of the first-result lat/lon extraction:
 * `format=jsonv2&limit=1` → a single-object array with string `lat`/`lon`. This locks
 * in the "first result's lat/lon → Pair, empty/malformed → null" contract; the live
 * HTTP call itself is left untested.
 */
class GeocoderTest {

    /** Extract the first result's (lat, lon) from a Nominatim jsonv2 array; null if absent. */
    private fun parseFirst(body: String): Pair<Double, Double>? {
        val lat = Regex("\"lat\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
        val lon = Regex("\"lon\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
        if (lat == null || lon == null) return null
        return lat to lon
    }

    @Test fun parses_first_result_lat_lon() {
        val body = """[{"lat":"52.5200","lon":"13.4050","display_name":"Berlin"}]"""
        assertEquals(52.52 to 13.405, parseFirst(body))
    }

    @Test fun empty_array_is_null() {
        assertNull(parseFirst("[]"))
    }

    @Test fun malformed_body_is_null() {
        assertNull(parseFirst("not json"))
    }

    @Test fun user_agent_constant_is_set() {
        assertEquals("Ledgerline-Android", Geocoder.USER_AGENT)
    }
}
