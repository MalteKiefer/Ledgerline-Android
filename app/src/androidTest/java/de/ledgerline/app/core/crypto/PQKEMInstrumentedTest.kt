package de.ledgerline.app.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full hybrid-KEM roundtrip on-device (needs native libsodium for the X25519 +
 * secretbox legs). The ML-KEM FIPS-203 byte-exactness is proven separately by the
 * pure-JVM `PQKEMKatTest`; this exercises the complete `hybridWrap`/`hybridUnwrap`
 * envelope end to end, matching the iOS E2E-lifecycle bar.
 */
@RunWith(AndroidJUnit4::class)
class PQKEMInstrumentedTest {

    private val pq = PQKEM()

    @Test fun hybrid_wrap_then_unwrap_recovers_payload() {
        val (xPub, xSk) = pq.x25519Keypair()
        val id = pq.mlkemKeypair()
        val payload = ByteArray(32) { it.toByte() } // a raw VK-sized key

        val env = pq.hybridWrap(payload, xPub, id.ek, context = "vault")
        assertTrue(env.suite == 1 && env.kem_ct.isNotEmpty() && env.epk.isNotEmpty())

        val out = pq.hybridUnwrap(env, xSk, id.seed, context = "vault")
        assertArrayEquals(payload, out)

        // Round-trips through the JSON envelope form too.
        val out2 = pq.hybridUnwrap(env.toJson(), xSk, id.seed, context = "vault")
        assertArrayEquals(payload, out2)
    }

    @Test fun wrong_context_fails_closed() {
        val (xPub, xSk) = pq.x25519Keypair()
        val id = pq.mlkemKeypair()
        val env = pq.hybridWrap(ByteArray(16), xPub, id.ek, context = "a")
        // HKDF domain-separation: a different context derives a different wrap key.
        assertNull(pq.hybridUnwrap(env, xSk, id.seed, context = "b"))
    }

    @Test fun wrong_recipient_fails_closed() {
        val (xPub, _) = pq.x25519Keypair()
        val id = pq.mlkemKeypair()
        val env = pq.hybridWrap(ByteArray(16), xPub, id.ek, context = "")

        val (_, otherSk) = pq.x25519Keypair()
        val otherId = pq.mlkemKeypair()
        assertNull(pq.hybridUnwrap(env, otherSk, otherId.seed, context = ""))
    }

    @Test fun unknown_suite_rejected() {
        val (xPub, xSk) = pq.x25519Keypair()
        val id = pq.mlkemKeypair()
        val env = pq.hybridWrap(ByteArray(16), xPub, id.ek)
        val tampered = env.copy(suite = 2)
        assertNull(pq.hybridUnwrap(tampered, xSk, id.seed))
    }
}
