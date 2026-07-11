package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.SessionHolder
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.domain.model.Session
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class ChunkedUploadTest {

    // ---- sliceParts (pure) ----------------------------------------------------

    @Test fun slice_exact_multiple() {
        assertEquals(
            listOf(1 to 0L..3L, 2 to 4L..7L),
            FileBlobRepository.sliceParts(8, 4),
        )
    }

    @Test fun slice_with_remainder() {
        assertEquals(
            listOf(1 to 0L..3L, 2 to 4L..7L, 3 to 8L..9L),
            FileBlobRepository.sliceParts(10, 4),
        )
    }

    @Test fun slice_single_part_when_partsize_exceeds_total() {
        assertEquals(listOf(1 to 0L..9L), FileBlobRepository.sliceParts(10, 64))
    }

    @Test fun slice_empty_for_zero_total() {
        assertTrue(FileBlobRepository.sliceParts(0, 4).isEmpty())
    }

    // ---- init / part / complete integration -----------------------------------

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    /** A [Crypto] whose encryptor emits `u32le(len) ++ plaintext` frames, no native lib. */
    private class FramingCrypto : SealTagCrypto() {
        override val contentChunkSize = 4
        override fun newContentEncryptor(vk: ByteArray): Crypto.ContentEncryptor =
            object : Crypto.ContentEncryptor {
                override val header = ByteArray(0)
                override fun encryptChunk(chunk: ByteArray, isLast: Boolean) = u32le(chunk.size) + chunk
                override fun sealKey() = "{\"c\":\"k\",\"n\":\"n\"}"
            }
    }

    private fun repo(): FileBlobRepository = FileBlobRepository(
        SessionHolder().apply { set(Session(server.url("/").toString(), "tok", "", null)) },
        VaultKeyHolder().apply { set(ByteArray(32)) },
        FramingCrypto(),
        tmpBlobCache(),
        FakeOfflineFlags(),
        cacheDir = File(System.getProperty("java.io.tmpdir")),
        apiProvider = { s -> de.ledgerline.app.data.remote.NetworkFactory.create(s.baseUrl, { s.token }, null, allowCleartext = true) },
    )

    @Test fun large_file_uses_init_part_complete() = runBlocking {
        val paths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                val path = req.path!!
                paths.add(path)
                return when {
                    path.endsWith("/upload/init") ->
                        MockResponse().setBody("""{"token":"tok-1","id":"blob-x","partSize":16}""")
                            .addHeader("Content-Type", "application/json")
                    path.endsWith("/upload/part") ->
                        MockResponse().setBody("""{"part":1,"etag":"e1"}""")
                            .addHeader("Content-Type", "application/json")
                    path.endsWith("/upload/complete") ->
                        MockResponse().setResponseCode(201).setBody("""{"id":"blob-x"}""")
                            .addHeader("Content-Type", "application/json")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // Force the chunked path regardless of THRESHOLD by feeding a big declared size;
        // the temp ciphertext is tiny so the run is fast. partSize=16 slices it into parts.
        val plaintext = ByteArray(40) { it.toByte() }
        val bigSize = FileBlobRepository.THRESHOLD + 1
        val res = repo().upload("big.bin", "application/octet-stream", bigSize) {
            ByteArrayInputStream(plaintext)
        }

        assertTrue("expected Ok, got $res", res is Outcome.Ok)
        assertEquals("blob-x", (res as Outcome.Ok).value.id)
        assertTrue(paths.any { it.endsWith("/upload/init") })
        assertTrue(paths.any { it.endsWith("/upload/part") })
        assertTrue(paths.any { it.endsWith("/upload/complete") })
    }

    @Test fun part_failure_aborts_and_errs() = runBlocking {
        val paths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                val path = req.path!!
                paths.add(path)
                return when {
                    path.endsWith("/upload/init") ->
                        MockResponse().setBody("""{"token":"tok-1","id":"blob-x","partSize":16}""")
                            .addHeader("Content-Type", "application/json")
                    path.endsWith("/upload/part") -> MockResponse().setResponseCode(500)
                    path.endsWith("/upload/abort") ->
                        MockResponse().setBody("""{"ok":true}""").addHeader("Content-Type", "application/json")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val bigSize = FileBlobRepository.THRESHOLD + 1
        val res = repo().upload("big.bin", "application/octet-stream", bigSize) {
            ByteArrayInputStream(ByteArray(40))
        }

        assertTrue("expected Err, got $res", res is Outcome.Err)
        assertTrue("expected abort call, saw $paths", paths.any { it.endsWith("/upload/abort") })
    }
}
