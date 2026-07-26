package de.ledgerline.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class RawBatchFramingTest {
    private fun u32le(n: Int) = byteArrayOf(
        (n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte(),
        ((n shr 16) and 0xFF).toByte(), ((n shr 24) and 0xFF).toByte(),
    )

    private fun frame(id: String, cipher: ByteArray): ByteArray {
        val idBytes = id.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().apply {
            write(u32le(idBytes.size)); write(idBytes); write(u32le(cipher.size)); write(cipher)
        }.toByteArray()
    }

    @Test fun parses_multiple_frames_keyed_by_id_in_order() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(9, 8)
        val bytes = frame("blob-a", a) + frame("blob-b", b)
        val out = RawBatchFraming.parse(bytes)
        assertEquals(listOf("blob-a", "blob-b"), out.keys.toList())
        assertArrayEquals(a, out["blob-a"])
        assertArrayEquals(b, out["blob-b"])
    }

    @Test fun stops_gracefully_on_a_truncated_trailing_frame() {
        val full = frame("blob-a", byteArrayOf(1, 2, 3))
        // Append a header claiming 100 bytes but provide none → the scan keeps only the good frame.
        val truncated = full + u32le(6) + "blob-b".toByteArray() + u32le(100)
        val out = RawBatchFraming.parse(truncated)
        assertEquals(setOf("blob-a"), out.keys)
    }

    @Test fun empty_input_yields_empty_map() {
        assertEquals(0, RawBatchFraming.parse(ByteArray(0)).size)
    }
}
