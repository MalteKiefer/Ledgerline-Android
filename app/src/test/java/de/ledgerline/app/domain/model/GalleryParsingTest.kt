package de.ledgerline.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parses_photos_with_unknown_and_missing_fields() {
        val text = """
          {"v":1,"gadget":true,
           "photos":[
             {"id":"p1","media_type":"image","thumbRef":"t1","thumbKey":"{}","created":"2026-01-02T00:00:00Z","extra":9},
             {"id":"p2","media_type":"video","trashed":true}
           ],
           "albums":[{"id":"a1","name":"Trip","photoIds":["p1"]}]}
        """.trimIndent()
        val m = json.decodeFromString<GalleryManifest>(text)
        assertEquals(2, m.photos.size)
        assertEquals("t1", m.photos[0].thumbRef)
        assertTrue(m.photos[1].trashed)
        assertEquals("Trip", m.albums[0].name)
        assertEquals(0, m.people.size)
    }
}
