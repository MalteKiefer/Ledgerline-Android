package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.CanonicalJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-exact round-trip against the web-generated fixture
 * (`app/src/test/resources/gallery-record-canonical.json`, produced by running the web's
 * real `canonical-json.js`). Decoding a web record and re-encoding it via
 * [GalleryRecordCodec] (raw-overlay) must reproduce the web client's exact canonical JSON —
 * proving no data loss and byte parity for the gallery store records (incl. `lat`/`lng`
 * dec6 strings and person `centroid` floats preserved verbatim).
 */
class GalleryRecordCodecTest {

    private fun readResource(path: String): String =
        javaClass.getResourceAsStream(path)!!.bufferedReader().readText()

    @Test fun records_round_trip_byte_exact_to_web_canonical() {
        val fixtures = Json.parseToJsonElement(readResource("/gallery-record-canonical.json")).jsonArray
        for (f in fixtures) {
            val o = f.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val record = o["record"]!!.jsonObject
            val expected = o["canonicalJSON"]!!.jsonPrimitive.content
            val actual = when {
                name.startsWith("photo") ->
                    CanonicalJson.encode(GalleryRecordCodec.encodePhoto(GalleryRecordCodec.decodePhoto(record), record))
                name.startsWith("album") ->
                    CanonicalJson.encode(GalleryRecordCodec.encodeAlbum(GalleryRecordCodec.decodeAlbum(record), record))
                else ->
                    CanonicalJson.encode(GalleryRecordCodec.encodePerson(GalleryRecordCodec.decodePerson(record), record))
            }
            assertEquals("record '$name' must round-trip to web canonical", expected, actual)
        }
    }
}
