package de.ledgerline.app.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PadmeTest {
    @Test fun small_values_unchanged() {
        assertEquals(0L, padmeSize(0)); assertEquals(1L, padmeSize(1))
    }
    @Test fun padme_never_shrinks_and_is_bounded() {
        for (n in longArrayOf(3, 100, 1000, 1024, 1_048_576, 5_000_000, 2_000_000_000)) {
            val p = padmeSize(n)
            assertTrue("padme($n)=$p must be >= n", p >= n)
            assertTrue("overhead <= ~12%", p <= n + n / 8 + 1)
        }
    }
    @Test fun pad_count_is_difference() {
        assertEquals(padmeSize(1000) - 1000, padByteCount(1000))
    }
}
