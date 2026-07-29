package de.ledgerline.app.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: web writes `"t": null` for timeless points (planned routes, KML LineStrings,
 *  GPX without <time>). Decoding must NOT crash the whole store load, and encoding must round-trip
 *  `null` (not `0`, which web would read as epoch 1970). */
class ExploreTrackCodecTest {

    private val json = Json

    private fun root(trackJson: String) =
        json.parseToJsonElement("""{"v":3,"tracks":[$trackJson]}""").jsonObject

    @Test fun decodes_timeless_null_t_without_crashing() {
        val m = ExploreTrackCodec.decodeManifest(
            root("""{"id":"a","name":"Planned","points":[
                {"lat":47.1,"lng":11.4,"ele":null,"t":null},
                {"lat":47.2,"lng":11.5,"ele":580.0,"t":1721599999000}
            ]}"""),
        )
        assertEquals(1, m.tracks.size)
        val pts = m.tracks[0].points
        assertEquals(2, pts.size)
        assertEquals(0L, pts[0].t)              // null → 0L sentinel, no NumberFormatException
        assertEquals(1721599999000L, pts[1].t)
    }

    @Test fun encodes_timeless_point_as_null_not_zero() {
        val m = ExploreTrackCodec.decodeManifest(
            root("""{"id":"a","name":"P","points":[{"lat":1.0,"lng":2.0,"ele":null,"t":null}]}"""),
        )
        val out = ExploreTrackCodec.encodeManifest(m)
        val pt = (out["tracks"] as JsonArray)[0].jsonObject["points"]!!.jsonArray[0].jsonObject
        assertTrue("timeless t must round-trip as JSON null, not 0", pt["t"] is JsonNull)
    }
}
