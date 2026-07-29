package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.Crypto
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BlobDownloaderTest {
    // Fake crypto: header = 2 bytes; each frame's "cipher" == plaintext (no MAC);
    // final flagged by a sentinel last byte (1 = final, 0 = not final).
    private val fake = object : Crypto {
        override val contentChunkSize = 8

        override fun readU32le(bytes: ByteArray, off: Int): Int =
            (bytes[off].toInt() and 0xff) or
                ((bytes[off + 1].toInt() and 0xff) shl 8) or
                ((bytes[off + 2].toInt() and 0xff) shl 16) or
                ((bytes[off + 3].toInt() and 0xff) shl 24)

        override fun u32le(n: Int): ByteArray = byteArrayOf(
            (n and 0xff).toByte(),
            ((n ushr 8) and 0xff).toByte(),
            ((n ushr 16) and 0xff).toByte(),
            ((n ushr 24) and 0xff).toByte(),
        )

        override fun contentDecryptorFromKey(fileKey: ByteArray): de.ledgerline.app.core.crypto.Crypto.ContentDecryptor = throw NotImplementedError()
        override fun contentDecryptor(encFileKey: String, vk: ByteArray) = object : Crypto.ContentDecryptor {
            override val headerBytes = 2
            override fun start(header: ByteArray) {}
            override fun decryptFrame(frame: ByteArray): Pair<ByteArray, Boolean> {
                val isFinal = frame.last() == 1.toByte()
                return frame.copyOf(frame.size - 1) to isFinal
            }
        }

        // Unused members — stubs so it compiles:
        override fun deriveKek(passphrase: ByteArray, salt: ByteArray, opsLimit: Long, memLimit: Long) = ByteArray(0)
        override fun secretBoxOpen(cipher: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? = null
        override fun genericHash32(input: ByteArray) = ByteArray(0)
        override fun b64decode(s: String) = ByteArray(0)
        override fun b64encode(b: ByteArray) = ""
        override fun fromHex(s: String) = ByteArray(0)
        override fun openManifest(ciphertext: String, vk: ByteArray): String? = null
        override fun sealManifest(json: String, vk: ByteArray) = ""
        override fun newContentEncryptor(vk: ByteArray): Crypto.ContentEncryptor = throw NotImplementedError()
    }

    @Test fun walks_frames_and_stops_on_final() {
        // blob = header(2) ++ frame1[u32len]["AB"+0] ++ frame2[u32len]["C"+1(final)] ++ garbage padme tail
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(9, 9))                          // header (2 bytes)
        val f1 = "AB".toByteArray() + byteArrayOf(0)          // not final
        out.write(fake.u32le(f1.size)); out.write(f1)
        val f2 = "C".toByteArray() + byteArrayOf(1)           // final
        out.write(fake.u32le(f2.size)); out.write(f2)
        out.write(byteArrayOf(7, 7, 7))                       // padme tail (ignored)

        val plain = BlobDownloader.decrypt(out.toByteArray(), "{}", ByteArray(32), fake)
        assertArrayEquals("ABC".toByteArray(), plain)
    }
}
