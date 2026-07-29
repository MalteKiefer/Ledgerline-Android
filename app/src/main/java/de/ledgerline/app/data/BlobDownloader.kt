package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.Crypto

/** Frame-decrypts a downloaded blob (secretstream) into plaintext bytes.
 *  Mirrors Phase-3 streamDecrypted; stops at TAG_FINAL, ignoring the Padmé tail. */
object BlobDownloader {
    fun decrypt(cipherBytes: ByteArray, encFileKey: String, vk: ByteArray, crypto: Crypto): ByteArray =
        drain(cipherBytes, crypto.contentDecryptor(encFileKey, vk), crypto)

    /** Decrypt a blob with an ALREADY-unwrapped raw per-file key (public share consumption — no VK). */
    fun decryptWithKey(cipherBytes: ByteArray, fileKey: ByteArray, crypto: Crypto): ByteArray =
        drain(cipherBytes, crypto.contentDecryptorFromKey(fileKey), crypto)

    private fun drain(cipherBytes: ByteArray, dec: Crypto.ContentDecryptor, crypto: Crypto): ByteArray {
        dec.start(cipherBytes.copyOfRange(0, dec.headerBytes))
        val out = java.io.ByteArrayOutputStream()
        var off = dec.headerBytes
        while (off < cipherBytes.size) {
            if (off + 4 > cipherBytes.size) break
            val len = crypto.readU32le(cipherBytes, off); off += 4
            if (len <= 0 || off + len > cipherBytes.size) break   // Padmé tail
            val (msg, final) = dec.decryptFrame(cipherBytes.copyOfRange(off, off + len)); off += len
            out.write(msg)
            if (final) break
        }
        return out.toByteArray()
    }
}
