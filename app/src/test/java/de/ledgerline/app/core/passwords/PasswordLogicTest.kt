package de.ledgerline.app.core.passwords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordLogicTest {

    // ---- TOTP: RFC 6238 test vector (SHA-1, secret = ASCII "12345678901234567890") ----
    // base32 of that ASCII secret; at T=59s → counter 1 → 94287082.
    private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test fun totp_matches_rfc6238_vector() {
        // RFC-6238 table is 8-digit; the web client uses 6 digits (mod 10^6): 94287082→287082.
        assertEquals("287082", Totp.code(rfcSecret, nowSeconds = 59))
        assertEquals("081804", Totp.code(rfcSecret, nowSeconds = 1111111109))
    }

    @Test fun base32_decodes_ascii_secret() {
        val bytes = Totp.base32Decode(rfcSecret)!!
        assertEquals("12345678901234567890", String(bytes, Charsets.US_ASCII))
        // lowercase + spaces + padding tolerated
        assertEquals(bytes.toList(), Totp.base32Decode("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")!!.toList())
        assertEquals(null, Totp.base32Decode("not-base32-!!!"))
    }

    @Test fun totp_countdown_in_range() {
        val r = Totp.secondsRemaining(nowSeconds = 10)
        assertTrue(r in 1..30)
        assertEquals(30, Totp.secondsRemaining(nowSeconds = 0))
    }

    // ---- Strength (web pwScore parity) ----
    @Test fun strength_score_and_weak_flag() {
        assertEquals(0, PasswordStrength.score(""))
        assertEquals(1, PasswordStrength.score("abcdefgh"))        // len8 only
        assertEquals(4, PasswordStrength.score("Abcd1234!"))       // len8 + mixed case + digit + symbol
        assertEquals(4, PasswordStrength.score("SuperLong12!xyz")) // capped at 4
        assertTrue(PasswordStrength.isWeak("abcdefgh"))
        assertFalse(PasswordStrength.isWeak("Abcd1234!"))
    }

    // ---- Breach (HIBP k-anonymity) ----
    @Test fun breach_sha1_prefix_suffix_and_count() {
        val hex = BreachCheck.sha1Hex("password")
        assertEquals("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8", hex)
        assertEquals("5BAA6", BreachCheck.prefix(hex))
        val suffix = BreachCheck.suffix(hex)
        assertEquals("1E4C9B93F3F0682250B6CF8331B7EE68FD8", suffix)
        val range = "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n$suffix:99999\r\nDEADBEEF:2"
        assertEquals(99999, BreachCheck.countInRange(range, suffix))
        assertEquals(0, BreachCheck.countInRange("SOMETHINGELSE:5", suffix))
    }
}
