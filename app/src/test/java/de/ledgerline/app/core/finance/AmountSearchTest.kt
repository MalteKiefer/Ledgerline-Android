package de.ledgerline.app.core.finance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountSearchTest {
    @Test fun matches_signed_and_absolute() {
        assertTrue(AmountSearch.amountMatches(-9.88, "-9"))
        assertTrue(AmountSearch.amountMatches(9.88, "9,88"))
        assertTrue(AmountSearch.amountMatches(-20.28, "-20"))
        assertTrue(AmountSearch.amountMatches(-9.88, "9.88"))     // absolute rendering
        assertFalse(AmountSearch.amountMatches(133.88, "-20"))
        assertFalse(AmountSearch.amountMatches(10.0, "abc"))       // no digits
        assertTrue(AmountSearch.amountMatches(1234.50, "1234"))
        assertTrue(AmountSearch.amountMatches(-5.0, "€5,00"))      // currency + separators stripped
    }
}
