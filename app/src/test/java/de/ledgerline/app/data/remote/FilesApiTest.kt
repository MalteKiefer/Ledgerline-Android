package de.ledgerline.app.data.remote

import de.ledgerline.app.data.remote.dto.StorePutRequest
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FilesApiTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun api() = cleartextApi(server.url("/").toString(), { "tok" })

    @Test fun upload_returns_id() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"blob-1"}""").addHeader("Content-Type", "application/json"))
        val part = MultipartBody.Part.createFormData("file", "a.bin", "cipher".toByteArray().toRequestBody("application/octet-stream".toMediaType()))
        val res = api().uploadFile(part)
        assertEquals("blob-1", res.body()!!.id)
    }

    @Test fun putStore_returns_new_version() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version":8}""").addHeader("Content-Type", "application/json"))
        val res = api().putStore(StorePutRequest("ct", 7))
        assertEquals(8, res.body()!!.version)
    }

    @Test fun putStore_conflict_is_409() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409))
        val res = api().putStore(StorePutRequest("ct", 1))
        assertEquals(409, res.code())
    }
}
