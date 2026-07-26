package de.ledgerline.app.core.crypto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical JSON, byte-compatible with the web `resources/js/shared/canonical-json.js`.
 * The content-addressed shard/collection hashes (§ Store v3 sharded gallery/files) are
 * SHA-256 over these exact bytes, so every rule here must match the web client:
 *
 *  - **Object keys sorted by Unicode code point** (NOT UTF-16 code unit / `String.compareTo`).
 *  - Compact separators, zero insignificant whitespace.
 *  - Strings escaped exactly like ECMAScript `JSON.stringify`: escape only `"` and `\`,
 *    the short forms `\b \f \n \r \t`, other controls as lowercase `\u00xx`; everything
 *    else (incl. non-ASCII / astral) literal UTF-8. No `/` escaping, no Unicode normalization.
 *  - Numbers on the hashed path are **integers only** (lat/lng etc. arrive pre-formatted as
 *    `%.6f` strings via [dec6]); rendered by their literal token.
 *  - `null` → `null`; an object property that is JSON `null` is kept (build the tree without
 *    the key to drop it, mirroring web dropping `undefined`).
 */
object CanonicalJson {
    fun encode(element: JsonElement): String = buildString { emit(element) }

    /** UTF-8 bytes of [encode] — the exact bytes that are sealed/hashed. */
    fun bytes(element: JsonElement): ByteArray = encode(element).toByteArray(Charsets.UTF_8)

    // Named `emit` (not `append`) so it is NOT shadowed by StringBuilder.append(Any?),
    // which would silently fall back to JsonElement.toString() (unsorted).
    private fun StringBuilder.emit(el: JsonElement) {
        when (el) {
            is JsonNull -> append("null")
            is JsonObject -> {
                append('{')
                var first = true
                val sortedKeys = el.keys.sortedWith(Comparator { a, b -> compareCodePoints(a, b) })
                sortedKeys.forEach { k ->
                    if (!first) append(',')
                    first = false
                    encodeString(k)
                    append(':')
                    emit(el.getValue(k))
                }
                append('}')
            }
            is JsonArray -> {
                append('[')
                el.forEachIndexed { i, v ->
                    if (i > 0) append(',')
                    emit(v)
                }
                append(']')
            }
            is JsonPrimitive -> {
                if (el.isString) encodeString(el.content) else append(el.content) // bool/number literal
            }
        }
    }

    /** ECMAScript `JSON.stringify` string escaping. */
    private fun StringBuilder.encodeString(s: String) {
        append('"')
        for (c in s) {
            when (c.code) {
                0x22 -> append("\\\"")   // "
                0x5C -> append("\\\\")   // \
                0x08 -> append("\\b")
                0x0C -> append("\\f")
                0x0A -> append("\\n")
                0x0D -> append("\\r")
                0x09 -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** Compare two strings by Unicode code point (matches JS iterator/`codePointAt` order). */
    private fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return if (ca < cb) -1 else 1
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        return (a.length - i).compareTo(b.length - j)
    }
}

/**
 * Fixed-6-decimal string for a coordinate/measure stored in a hashed record (web `dec6`):
 * null/blank/non-finite → null, else `"%.6f"` (locale-independent). Kept as a string so the
 * hashed record never carries a float. Byte-compatible with web's `Number#toFixed(6)`.
 */
fun dec6(value: Double?): String? {
    if (value == null || !value.isFinite()) return null
    return String.format(java.util.Locale.ROOT, "%.6f", value)
}
