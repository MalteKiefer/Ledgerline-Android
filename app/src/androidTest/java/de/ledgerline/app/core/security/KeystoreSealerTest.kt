package de.ledgerline.app.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreSealerTest {
    // requireAuth=false variant so the test needs no BiometricPrompt.
    private val sealer = KeystoreSealer(alias = "ledgerline_test_key", requireAuth = false)

    @Before
    fun setUp() {
        // Ensure a clean key state before each test.
        sealer.clear()
    }

    @After
    fun tearDown() {
        // Clean up after each test run.
        sealer.clear()
    }

    @Test fun seal_then_open_roundtrips() {
        val secret = "bearer-token-xyz".toByteArray()
        val blob = sealer.seal(secret)
        assertFalse(blob.isEmpty())
        assertArrayEquals(secret, sealer.open(blob))
    }

    @Test fun ciphertext_is_not_plaintext() {
        val secret = "bearer-token-xyz".toByteArray()
        val blob = sealer.seal(secret)
        assertFalse(String(blob).contains("bearer-token-xyz"))
    }
}
