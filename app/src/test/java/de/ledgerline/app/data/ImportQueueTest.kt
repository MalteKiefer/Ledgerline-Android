package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.Crypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class ImportQueueTest {

    /** Identity content crypto that still frames like the real one: encryptChunk = u32le(len) ++ payload
     *  ++ final-tag byte; decryptFrame strips the tag. So seal→open round-trips the exact bytes. */
    private val crypto = object : Crypto {
        override fun sealManifest(json: String, vk: ByteArray) = "S:$json"
        override fun openManifest(ciphertext: String, vk: ByteArray) = if (ciphertext.startsWith("S:")) ciphertext.removePrefix("S:") else null
        override fun sealValue(data: ByteArray, key: ByteArray) = String(data, Charsets.ISO_8859_1)
        override fun openValue(cn: String, key: ByteArray) = cn.toByteArray(Charsets.ISO_8859_1)
        override fun genericHash(input: ByteArray, outLen: Int) = ByteArray(outLen)
        override fun deriveKek(passphrase: ByteArray, salt: ByteArray, opsLimit: Long, memLimit: Long) = ByteArray(32)
        override fun secretBoxOpen(cipher: ByteArray, nonce: ByteArray, key: ByteArray) = ByteArray(0)
        override fun genericHash32(input: ByteArray) = ByteArray(32)
        override fun b64decode(s: String) = s.toByteArray()
        override fun b64encode(b: ByteArray) = String(b)
        override fun fromHex(s: String) = s.toByteArray()
        override val contentChunkSize = 8
        override fun u32le(n: Int) = byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte())
        override fun readU32le(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
        override fun newContentEncryptor(vk: ByteArray): Crypto.ContentEncryptor = object : Crypto.ContentEncryptor {
            override val header = ByteArray(24)
            override fun encryptChunk(chunk: ByteArray, isLast: Boolean): ByteArray {
                val payload = chunk + byteArrayOf(if (isLast) 1 else 0)
                return u32le(payload.size) + payload
            }
            override fun sealKey() = """{"c":"x","n":"y"}"""
        }
        override fun contentDecryptorFromKey(fileKey: ByteArray): Crypto.ContentDecryptor = decryptor()
        override fun contentDecryptor(encFileKey: String, vk: ByteArray): Crypto.ContentDecryptor = decryptor()
        private fun decryptor() = object : Crypto.ContentDecryptor {
            override val headerBytes = 24
            override fun start(header: ByteArray) {}
            override fun decryptFrame(frame: ByteArray) = Pair(frame.copyOfRange(0, frame.size - 1), frame.last().toInt() == 1)
        }
    }

    private fun tmpDir(): File = Files.createTempDirectory("import_queue_test").toFile()
    private fun queue() = ImportQueue(tmpDir(), crypto)
    private val vk = ByteArray(32)

    @Test fun seals_and_reads_back_exact_bytes() = runBlocking {
        val q = queue()
        val bytes = ByteArray(20) { it.toByte() } // spans multiple 8-byte chunks
        val sig = ContentSig.of({ ByteArrayInputStream(bytes) }, bytes.size.toLong())

        assertTrue(q.enqueueFile(vk, "doc.pdf", "application/pdf", bytes.size.toLong(), sig, { ByteArrayInputStream(bytes) }, folder = "f1"))
        assertTrue(q.hasPending())

        val items = q.pending(vk)
        assertEquals(1, items.size)
        val h = items.first()
        assertEquals("doc.pdf", h.item.name)
        assertEquals(ImportQueue.Kind.FILE.name, h.item.kind)
        assertEquals("f1", h.item.folder)
        assertArrayEquals(bytes, h.open().use { it.readBytes() })   // decrypting stream yields the source
    }

    @Test fun dedupes_by_sig() = runBlocking {
        val q = queue()
        val bytes = ByteArray(10) { 7 }
        val sig = ContentSig.of({ ByteArrayInputStream(bytes) }, bytes.size.toLong())
        q.enqueueFile(vk, "a.pdf", "application/pdf", 10, sig, { ByteArrayInputStream(bytes) }, folder = null)
        q.enqueueFile(vk, "a-again.pdf", "application/pdf", 10, sig, { ByteArrayInputStream(bytes) }, folder = null)
        assertEquals(1, q.pending(vk).size)
    }

    @Test fun remove_clears_the_item() = runBlocking {
        val q = queue()
        val bytes = ByteArray(5) { 3 }
        val sig = ContentSig.of({ ByteArrayInputStream(bytes) }, 5)
        q.enqueueFile(vk, "doc.pdf", "application/pdf", 5, sig, { ByteArrayInputStream(bytes) }, folder = "f1")
        val id = q.pending(vk).first().item.id
        q.remove(vk, id)
        assertFalse(q.hasPending())
        assertTrue(q.pending(vk).isEmpty())
    }

    @Test fun empty_when_locked_or_never_queued() = runBlocking {
        val q = queue()
        assertFalse(q.hasPending())
        assertTrue(q.pending(vk).isEmpty())
    }
}
