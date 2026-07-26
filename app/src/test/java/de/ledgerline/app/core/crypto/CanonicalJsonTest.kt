package de.ledgerline.app.core.crypto

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Byte-exactness of [CanonicalJson] against the web fixture
 * (`ledgerline/resources/js/__tests__/fixtures/store-v3/canonical-json.json`, §17).
 * Matching these guarantees the sharded gallery/files hashes agree with web + iOS.
 */
class CanonicalJsonTest {

    @Test fun sorts_keys_and_is_compact() {
        val o = buildJsonObject { put("b", 1); put("a", 2); put("c", 3) }
        assertEquals("""{"a":2,"b":1,"c":3}""", CanonicalJson.encode(o))
    }

    @Test fun nested_objects_sorted_arrays_preserve_order() {
        val o = buildJsonObject {
            put("z", buildJsonArray { add(JsonPrimitive(3)); add(JsonPrimitive(1)); add(JsonPrimitive(2)) })
            put("a", buildJsonObject { put("y", 1); put("x", 2) })
        }
        assertEquals("""{"a":{"x":2,"y":1},"z":[3,1,2]}""", CanonicalJson.encode(o))
    }

    @Test fun null_bool_negative_int() {
        val o = buildJsonObject {
            put("n", JsonNull)
            put("t", true); put("f", false); put("i", -7)
        }
        assertEquals("""{"f":false,"i":-7,"n":null,"t":true}""", CanonicalJson.encode(o))
    }

    @Test fun dec_string_lat_lng_kept_as_strings() {
        val o = buildJsonObject { put("lat", "52.520008"); put("lng", "13.404954") }
        assertEquals("""{"lat":"52.520008","lng":"13.404954"}""", CanonicalJson.encode(o))
    }

    @Test fun minimal_escaping_and_literal_unicode() {
        // value = a " b \ c é  → only " and \ escaped, é literal.
        val o = buildJsonObject { put("key", "a\"b\\cé") }
        assertEquals("{\"key\":\"a\\\"b\\\\cé\"}", CanonicalJson.encode(o))
    }

    @Test fun control_chars_short_and_u_forms() {
        // newline → \n (short), U+0001 →  (lowercase u-form).
        val value = "a" + "\n" + "b" + "" + "c"
        val o = buildJsonObject { put("k", value) }
        assertEquals("{\"k\":\"a\\nb\\u0001c\"}", CanonicalJson.encode(o))
    }

    @Test fun empty_object_and_array() {
        val o = buildJsonObject { put("o", buildJsonObject { }); put("a", buildJsonArray { }) }
        assertEquals("""{"a":[],"o":{}}""", CanonicalJson.encode(o))
    }

    @Test fun dec6_formats_and_guards() {
        assertEquals("52.520008", dec6(52.520008))
        assertEquals("0.000000", dec6(0.0))
        assertNull(dec6(null))
        assertNull(dec6(Double.NaN))
        assertNull(dec6(Double.POSITIVE_INFINITY))
    }
}
