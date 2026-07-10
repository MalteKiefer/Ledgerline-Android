package de.ledgerline.app.data.remote

import de.ledgerline.app.data.remote.dto.ProcessResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GalleryDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parses_process_response_with_opaque_and_missing_fields() {
        val text = """
          {"thumb":"AAA","medium":"BBB","exif":{"camera":"Pixel","lat":51.1,"lon":6.9,"taken_at":"2026-01-01","iso":100},
           "place":{"city":"Köln","country":"DE"},"embedding":[0.1,0.2],"phash":"ff00",
           "faces":[{"score":0.9,"box":[1,2,3,4],"embedding":[0.3],"crop":"CCC"}],
           "width":4000,"height":3000,"content_id":"abc","surprise":1}
        """.trimIndent()
        val p = json.decodeFromString<ProcessResponse>(text)
        assertEquals("AAA", p.thumb)
        assertEquals(4000, p.width)
        assertEquals(1, p.faces.size)
        assertEquals("CCC", p.faces[0].crop)
        assertNotNull(p.exif)      // opaque, preserved
        assertNotNull(p.place)
    }
}
