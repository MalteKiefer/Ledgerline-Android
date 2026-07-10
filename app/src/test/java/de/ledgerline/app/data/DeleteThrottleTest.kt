package de.ledgerline.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DeleteThrottleTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun retries_on_429_then_succeeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200))

        val repo = FileBlobRepository.forTest(server.url("/").toString())
        repo.deleteBlobs(listOf("blob-x"))

        assertEquals(2, server.requestCount)
    }
}
