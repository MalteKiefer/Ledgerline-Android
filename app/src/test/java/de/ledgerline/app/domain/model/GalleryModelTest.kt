package de.ledgerline.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun meta_blob_parses_embedding_faces_place_and_phash_and_ignores_unknown() {
        val src = """{"place":{"city":"X"},"embedding":[0.1,0.2],""" +
            """"faces":[{"embedding":[0.3],"cropRef":"r","cropKey":"k"}],""" +
            """"phash":123456789,"unknown":true}"""

        val meta = json.decodeFromString(PhotoMetaBlob.serializer(), src)

        assertEquals("X", meta.place?.city)
        assertEquals(listOf(0.1, 0.2), meta.embedding)
        assertEquals(1, meta.faces.size)
        assertEquals(listOf(0.3), meta.faces[0].embedding)
        assertEquals("r", meta.faces[0].cropRef)
        assertEquals("k", meta.faces[0].cropKey)
        assertEquals(123456789L, meta.phash)
    }

    @Test
    fun meta_blob_phash_defaults_to_null_when_absent() {
        val meta = json.decodeFromString(
            PhotoMetaBlob.serializer(),
            """{"embedding":[0.1]}""",
        )

        assertNull(meta.phash)
    }

    @Test
    fun person_parses_faces_array() {
        val src = """{"id":"p1","name":"Alice","hidden":false,""" +
            """"centroid":[0.5,0.6],""" +
            """"faces":[{"photoId":"ph1","idx":2,"cropRef":"cr","cropKey":"ck"}]}"""

        val person = json.decodeFromString(GalleryPerson.serializer(), src)

        assertEquals("p1", person.id)
        assertEquals("Alice", person.name)
        assertEquals(listOf(0.5, 0.6), person.centroid)
        assertEquals(1, person.faces.size)
        assertEquals("ph1", person.faces[0].photoId)
        assertEquals(2, person.faces[0].idx)
        assertEquals("cr", person.faces[0].cropRef)
        assertEquals("ck", person.faces[0].cropKey)
    }

    @Test
    fun person_defaults_to_empty_faces_when_absent() {
        val person = json.decodeFromString(
            GalleryPerson.serializer(),
            """{"id":"p2","name":"Bob"}""",
        )

        assertTrue(person.faces.isEmpty())
    }
}
