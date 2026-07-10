package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.data.remote.dto.ProcessFace
import de.ledgerline.app.data.remote.dto.ProcessResponse
import de.ledgerline.app.domain.usecase.GalleryUploadApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GalleryUploaderTest {

    /** Fake upload/process surface: each uploadBytes returns a fresh incrementing blob id. */
    private class FakeApi(val process: ProcessResponse) : GalleryUploadApi {
        var uploads = 0
        override suspend fun uploadBytes(bytes: ByteArray, name: String): Outcome<UploadedBlob> {
            uploads++
            return Outcome.Ok(UploadedBlob(id = "blob-$uploads", encFileKey = "{c,n}", size = bytes.size.toLong()))
        }

        override suspend fun process(bytes: ByteArray, name: String, mime: String): Outcome<ProcessResponse> =
            Outcome.Ok(process)
    }

    /** Off-device the android.util.Base64 stub throws, so decode via java.util.Base64. */
    private fun uploader(api: GalleryUploadApi) = object : GalleryUploader(api) {
        override fun decodeBase64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
    }

    @Test
    fun uploads_photo_and_builds_entry() = runBlocking {
        val process = ProcessResponse(
            thumb = "AAA",
            medium = "BBB",
            exif = Json.parseToJsonElement("""{"camera":"Pixel","lat":51.1,"lon":6.9,"taken_at":"2026-01-02"}"""),
            faces = listOf(
                ProcessFace(
                    score = 0.9,
                    box = Json.parseToJsonElement("[1]"),
                    embedding = Json.parseToJsonElement("[0.1]"),
                    crop = "CCC",
                ),
            ),
            width = 4000,
            height = 3000,
            content_id = "cid",
        )
        val api = FakeApi(process)

        val out = uploader(api).upload("a.jpg", "image/jpeg", "sig1", byteArrayOf(1, 2, 3), "2026-01-02T00:00:00Z")
        val photo = (out as Outcome.Ok).value

        assertNotNull(photo.originalRef)
        assertNotNull(photo.thumbRef)
        assertNotNull(photo.mediumRef)
        assertNotNull(photo.metaRef)
        assertEquals(1, photo.faceCropRefs.size)
        assertEquals("Pixel", photo.camera)
        assertEquals(51.1, photo.lat!!, 1e-9)
        assertEquals(6.9, photo.lng!!, 1e-9)
        assertEquals("2026-01-02", photo.taken_at)
        assertEquals(1, photo.hasFaces)
        assertEquals("sig1", photo.sig)
        assertEquals("image", photo.media_type)
    }
}
