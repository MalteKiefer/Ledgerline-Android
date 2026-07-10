package de.ledgerline.app.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ContentCipherTest {
    private val crypto = SodiumCrypto()
    private val vk = ByteArray(32) { (it * 7 + 1).toByte() }

    private fun encrypt(plain: ByteArray): Pair<ByteArray, String> {
        val enc = crypto.newContentEncryptor(vk)
        val out = ByteArrayOutputStream()
        out.write(enc.header)
        val chunk = crypto.contentChunkSize
        var off = 0
        do {
            val end = minOf(off + chunk, plain.size)
            val slice = plain.copyOfRange(off, end)
            val last = end >= plain.size
            out.write(enc.encryptChunk(slice, last))
            off = end
        } while (off < plain.size)
        return out.toByteArray() to enc.sealKey()
    }

    private fun decrypt(blob: ByteArray, encFileKey: String): ByteArray {
        val dec = crypto.contentDecryptor(encFileKey, vk)
        dec.start(blob.copyOfRange(0, dec.headerBytes))
        val out = ByteArrayOutputStream()
        var off = dec.headerBytes
        while (off < blob.size) {
            val len = crypto.readU32le(blob, off); off += 4
            val (msg, final) = dec.decryptFrame(blob.copyOfRange(off, off + len)); off += len
            out.write(msg)
            if (final) break
        }
        return out.toByteArray()
    }

    @Test fun roundtrips_multichunk() {
        val plain = ByteArray(9 * 1024 * 1024) { (it % 251).toByte() } // spans 3 chunks
        val (blob, key) = encrypt(plain)
        assertArrayEquals(plain, decrypt(blob, key))
    }

    @Test fun roundtrips_small_and_empty() {
        val small = "hello vault".toByteArray()
        val (b1, k1) = encrypt(small)
        assertArrayEquals(small, decrypt(b1, k1))
        val (b2, k2) = encrypt(ByteArray(0))
        assertEquals(0, decrypt(b2, k2).size)
    }
}
