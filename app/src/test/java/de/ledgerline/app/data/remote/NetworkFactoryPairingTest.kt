package de.ledgerline.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class NetworkFactoryPairingTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun poll_returns_approved_token() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"status":"approved","token":"tok123","user":{"id":1,"name":"Malte"}}""")
                .addHeader("Content-Type", "application/json")
        )
        val api = NetworkFactory.create(server.url("/").toString(), tokenProvider = { null }, pin = null)
        val res = api.pollPair("abc")
        assertEquals(200, res.code())
        assertEquals("approved", res.body()!!.status)
        assertEquals("tok123", res.body()!!.token)
    }
}
