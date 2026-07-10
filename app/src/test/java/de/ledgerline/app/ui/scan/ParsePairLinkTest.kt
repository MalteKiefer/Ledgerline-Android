package de.ledgerline.app.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsePairLinkTest {
    @Test fun parses_valid_link() {
        val r = parsePairLink("ledgerline://pair?url=https%3A%2F%2Fhome.example&code=abc123")
        assertEquals("https://home.example" to "abc123", r)
    }
    @Test fun rejects_http_url() {
        assertNull(parsePairLink("ledgerline://pair?url=http%3A%2F%2Fhome.example&code=abc"))
    }
    @Test fun rejects_wrong_scheme() {
        assertNull(parsePairLink("https://pair?url=https%3A%2F%2Fx&code=abc"))
    }
}
