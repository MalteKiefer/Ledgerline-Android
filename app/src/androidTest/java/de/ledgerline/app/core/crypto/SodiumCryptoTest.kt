package de.ledgerline.app.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SodiumCryptoTest {
    private val crypto = SodiumCrypto()

    @Test fun secretBoxOpen_recovers_plaintext_and_rejects_wrong_key() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(24) { (it + 7).toByte() }
        val plaintext = "vault-key-material".toByteArray()
        val cipher = crypto.secretBoxSealForTest(plaintext, nonce, key)

        assertArrayEquals(plaintext, crypto.secretBoxOpen(cipher, nonce, key))
        val wrong = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(crypto.secretBoxOpen(cipher, nonce, wrong))
    }

    @Test fun deriveKek_is_deterministic_for_same_inputs() {
        val salt = ByteArray(16) { it.toByte() }
        val a = crypto.deriveKek("correct horse".toByteArray(), salt, 2, 67108864)
        val b = crypto.deriveKek("correct horse".toByteArray(), salt, 2, 67108864)
        assertArrayEquals(a, b)
        assert(a.size == 32)
    }

    @Test fun genericHash32_matches_length_and_determinism() {
        val h1 = crypto.genericHash32(byteArrayOf(1, 2, 3))
        val h2 = crypto.genericHash32(byteArrayOf(1, 2, 3))
        assertArrayEquals(h1, h2)
        assert(h1.size == 32)
    }

    @Test fun base64_is_original_variant_padded() {
        val bytes = byteArrayOf(0, 1, 2, 3, 4)
        assert(crypto.b64encode(bytes) == "AAECAwQ=")
        assertArrayEquals(bytes, crypto.b64decode("AAECAwQ="))
    }
}
