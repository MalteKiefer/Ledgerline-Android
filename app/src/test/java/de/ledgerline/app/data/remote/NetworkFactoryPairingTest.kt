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
        val api = cleartextApi(server.url("/").toString(), tokenProvider = { null })
        val res = api.pollPair(de.ledgerline.app.data.remote.dto.PairCollectRequest("abc"))
        assertEquals(200, res.code())
        // Verify the app now polls via POST /auth/pair/collect (not the old GET).
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/auth/pair/collect", recorded.path)
        assertEquals("approved", res.body()!!.status)
        assertEquals("tok123", res.body()!!.token)
    }
}
