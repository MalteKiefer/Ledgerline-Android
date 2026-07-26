package de.ledgerline.app.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * ML-KEM-768 FIPS-203 conformance against the committed NIST KAT
 * (`ledgerline/resources/js/__tests__/fixtures/store-v3/mlkem768-kat.json`, §17).
 * A byte-exact match here proves the Android PQ leg ([MlKem768], BouncyCastle) is
 * interoperable with the web (@noble) and iOS (CryptoKit) clients: same 64-byte
 * seed → identical ek, and deterministic encapsulation → identical ciphertext +
 * shared secret. Pure JVM (no native libsodium), so it runs as a unit test.
 */
class PQKEMKatTest {
    // Fixture: seed = 0x00..0x3f (64 B); msgSeed = 0xc8..0xa9 (32 B).
    private val seed = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
    private val msgSeed = hex("c8c7c6c5c4c3c2c1c0bfbebdbcbbbab9b8b7b6b5b4b3b2b1b0afaeadacabaaa9")
    private val ekSha256 = "0b7934c83125c788995e2ba6bd761e33046b3e40571be53e023309a29f398cc9"
    private val ctSha256 = "754478f91c2bb04f7ed790063d790491f53ae91ec4c0f7fe75a48593002d3ef0"
    private val sharedSecret = "9be827c5722456b406997307ed9f552b0f128bd2e9f7a7b352d4ba08bfb8e0ec"

    @Test fun keygen_from_seed_matches_kat() {
        val ek = MlKem768.ekFromSeed(seed)
        assertEquals(1184, ek.size)
        assertEquals(ekSha256, sha256(ek))
    }

    @Test fun deterministic_encapsulation_matches_kat() {
        val ek = MlKem768.ekFromSeed(seed)
        val (ct, ss) = MlKem768.encapsulateDeterministic(ek, msgSeed)
        assertEquals(1088, ct.size)
        assertEquals(ctSha256, sha256(ct))
        assertEquals(sharedSecret, hexOf(ss))
    }

    @Test fun decapsulation_recovers_shared_secret() {
        val ek = MlKem768.ekFromSeed(seed)
        val (ct, _) = MlKem768.encapsulateDeterministic(ek, msgSeed)
        val ss = MlKem768.decapsulate(seed, ct)
        assertEquals(sharedSecret, hexOf(ss))
    }

    private fun sha256(b: ByteArray): String = hexOf(MessageDigest.getInstance("SHA-256").digest(b))
    private fun hexOf(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
