package de.ledgerline.app.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.ledgerline.app.data.remote.dto.VaultKeysResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sharing-identity roundtrip on-device (needs native libsodium for secretbox +
 * BLAKE2b): generate → publish-body (secrets sealed under VK) → parse back as a
 * server response → unwrap → identical secrets, and those secrets actually decrypt a
 * hybrid envelope wrapped to the identity's public keys.
 */
@RunWith(AndroidJUnit4::class)
class IdentityCryptoInstrumentedTest {
    private val crypto = SodiumCrypto()
    private val pq = PQKEM()
    private val ic = IdentityCrypto(pq, crypto)
    private val vk = ByteArray(32) { it.toByte() }

    @Test fun publish_then_unwrap_recovers_identical_identity() {
        val id = ic.generate()
        val body = ic.publishBody(id, vk)

        // Public + fingerprint are byte-stable.
        assertEquals(crypto.b64encode(id.x25519Pub), body.public_key)
        assertEquals(crypto.b64encode(id.mlkemEk), body.mlkem_public_key)
        assertEquals(ic.fingerprintHex(id.x25519Pub), body.fingerprint)
        assertEquals(32, body.fingerprint.length) // BLAKE2b-16 → 32 hex chars

        // A server GET would return exactly these fields.
        val resp = VaultKeysResponse(
            public_key = body.public_key,
            wrapped_secret_key = body.wrapped_secret_key,
            fingerprint = body.fingerprint,
            mlkem_public_key = body.mlkem_public_key,
            wrapped_mlkem_secret_key = body.wrapped_mlkem_secret_key,
        )
        val back = ic.unwrap(resp, vk)!!
        assertArrayEquals(id.x25519Pub, back.x25519Pub)
        assertArrayEquals(id.x25519Sk, back.x25519Sk)
        assertArrayEquals(id.mlkemEk, back.mlkemEk)
        assertArrayEquals(id.mlkemSeed, back.mlkemSeed)

        // Wrong VK → fail-closed.
        assertNull(ic.unwrap(resp, ByteArray(32)))
        // Tampered fingerprint → fail-closed.
        assertNull(ic.unwrap(resp.copy(fingerprint = "0".repeat(32)), vk))
    }

    @Test fun unwrapped_secrets_decrypt_a_hybrid_envelope() {
        val id = ic.generate()
        val body = ic.publishBody(id, vk)
        val resp = VaultKeysResponse(
            body.public_key, body.wrapped_secret_key, body.fingerprint,
            body.mlkem_public_key, body.wrapped_mlkem_secret_key,
        )
        val back = ic.unwrap(resp, vk)!!

        val payload = "vault-key".toByteArray()
        val env = pq.hybridWrap(payload, back.x25519Pub, back.mlkemEk, context = "vault")
        assertArrayEquals(payload, pq.hybridUnwrap(env, back.x25519Sk, back.mlkemSeed, context = "vault"))
    }
}
