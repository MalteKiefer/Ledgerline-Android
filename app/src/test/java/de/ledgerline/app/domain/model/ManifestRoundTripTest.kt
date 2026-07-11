package de.ledgerline.app.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Data-integrity regression test.
 *
 * The repos decode sealed manifests with [Json.ignoreUnknownKeys] and re-encode
 * with [Json.encodeDefaults]. If a web-authored field is missing from our Kotlin
 * models, it is silently dropped on the next Android save. These tests prove that
 * every previously-missing web field now round-trips (decode -> encode -> decode).
 */
class ManifestRoundTripTest {

    private val decoder = Json { ignoreUnknownKeys = true }
    private val encoder = Json { encodeDefaults = true }

    private inline fun <reified T> roundTrip(input: String): T {
        val decoded = decoder.decodeFromString<T>(input)
        val reEncoded = encoder.encodeToString(decoded)
        return decoder.decodeFromString<T>(reEncoded)
    }

    @Test
    fun fileEntry_preserves_favorite_tags_and_versions() {
        val input = """
            {
              "id": "f1", "blob": "b1", "encFileKey": "{\"c\":\"x\",\"n\":\"y\"}",
              "name": "doc.txt", "mime": "text/plain", "size": 42, "folder": null,
              "created": "2026-01-01T00:00:00Z",
              "favorite": true,
              "tags": ["a", "b"],
              "versions": [
                { "id": "v1", "blob": "vb1", "encFileKey": "{\"c\":\"p\",\"n\":\"q\"}",
                  "size": 40, "mime": "text/plain", "name": "doc.txt",
                  "created": "2025-12-31T00:00:00Z" }
              ]
            }
        """.trimIndent()

        val result = roundTrip<FileEntry>(input)

        assertTrue("favorite must survive round-trip", result.favorite)
        assertEquals(listOf("a", "b"), result.tags)
        assertEquals(1, result.versions.size)
        val v = result.versions[0]
        assertEquals("v1", v.id)
        assertEquals("vb1", v.blob)
        assertEquals("{\"c\":\"p\",\"n\":\"q\"}", v.encFileKey)
        assertEquals(40L, v.size)
        assertEquals("text/plain", v.mime)
        assertEquals("doc.txt", v.name)
        assertEquals("2025-12-31T00:00:00Z", v.created)
    }

    @Test
    fun bookmark_preserves_read() {
        val input = """
            {
              "id": "bm1", "url": "https://example.com", "title": "Ex",
              "description": "d", "tags": ["t"], "folderId": "fold1",
              "favorite": true, "readLater": true, "read": true, "trashed": false
            }
        """.trimIndent()

        val result = roundTrip<Bookmark>(input)

        assertTrue("read must survive round-trip", result.read)
    }

    @Test
    fun namedFolder_preserves_color_and_icon() {
        val input = """
            {
              "id": "fold1", "name": "Docs", "parent": null,
              "color": "#fff", "icon": "star"
            }
        """.trimIndent()

        val result = roundTrip<NamedFolder>(input)

        assertEquals("#fff", result.color)
        assertEquals("star", result.icon)
    }

    @Test
    fun galleryPhoto_preserves_rotation_flip_favorite_and_failure_fields() {
        val input = """
            {
              "id": "p1", "media_type": "image",
              "rotation": 90, "flipH": true, "flipV": false,
              "favorite": true, "failed": true, "procError": "x"
            }
        """.trimIndent()

        val result = roundTrip<GalleryPhoto>(input)

        assertEquals(90, result.rotation)
        assertTrue("flipH must survive round-trip", result.flipH)
        assertTrue("favorite must survive round-trip", result.favorite)
        assertTrue("failed must survive round-trip", result.failed)
        assertEquals("x", result.procError)
    }
}
