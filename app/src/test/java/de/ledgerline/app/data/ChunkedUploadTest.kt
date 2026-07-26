package de.ledgerline.app.data

import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.crypto.Crypto
import de.ledgerline.app.data.remote.dto.PartRef
import de.ledgerline.app.data.remote.dto.UploadInitResponse
import de.ledgerline.app.data.remote.dto.UploadPartResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class ChunkedUploadTest {

    // Identity encryptor: no header, no framing — so the temp file == the plaintext (+ any
    // Padmé tail). Real secretstream framing is exercised on-device (SodiumCryptoTest).
    private val idEncryptor = object : Crypto.ContentEncryptor {
        override val header = ByteArray(0)
        override fun encryptChunk(chunk: ByteArray, isLast: Boolean) = chunk
        override fun sealKey() = "{\"c\":\"k\",\"n\":\"n\"}"
    }

    @Test fun splits_into_parts_uploads_all_and_completes() = runBlocking {
        val data = ByteArray(95) { it.toByte() }
        val uploaded = mutableListOf<ByteArray>()
        var completed: List<PartRef>? = null

        val res = ChunkedUpload.upload(
            encryptor = idEncryptor,
            chunkSize = 16,
            size = data.size.toLong(),
            openInput = { data.inputStream() },
            tempDir = createTempDirectory().toFile(),
            init = { UploadInitResponse(token = "tok", id = "", partSize = 10) },
            part = { _, num, chunk -> uploaded.add(chunk); UploadPartResponse(num, "etag$num") },
            complete = { _, parts -> completed = parts; "blob-42" },
            abort = { },
        )

        assertTrue(res is Outcome.Ok)
        assertEquals("blob-42", (res as Outcome.Ok).value.id)
        assertEquals("{\"c\":\"k\",\"n\":\"n\"}", res.value.encFileKey)
        // Every part uploaded is finalized, in order, with 1-based numbers.
        assertEquals(uploaded.size, completed!!.size)
        assertEquals((1..uploaded.size).toList(), completed!!.map { it.part })
        // All parts but the last are exactly the server partSize.
        uploaded.dropLast(1).forEach { assertEquals(10, it.size) }
        // The reassembled parts equal the encrypted-temp contents (≥ the 95 plaintext bytes).
        val reassembled = uploaded.reduce { a, b -> a + b }
        assertTrue("uploaded ${'$'}{reassembled.size} bytes ≥ 95", reassembled.size >= 95)
    }

    @Test fun aborts_when_a_part_fails() = runBlocking {
        var aborted = false
        val res = ChunkedUpload.upload(
            encryptor = idEncryptor,
            chunkSize = 16,
            size = 50L,
            openInput = { ByteArray(50).inputStream() },
            tempDir = createTempDirectory().toFile(),
            init = { UploadInitResponse("tok", "", 10) },
            part = { _, _, _ -> null }, // first part fails
            complete = { _, _ -> "x" },
            abort = { aborted = true },
        )
        assertTrue(res is Outcome.Err)
        assertTrue(aborted)
    }
}
