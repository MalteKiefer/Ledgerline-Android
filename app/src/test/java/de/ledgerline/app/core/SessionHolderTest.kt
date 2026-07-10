package de.ledgerline.app.core

import de.ledgerline.app.domain.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionHolderTest {
    @Test fun set_get_clear() {
        val h = SessionHolder()
        assertNull(h.get())
        val s = Session("https://h.example", "tok", "sha256/AAA", "Malte")
        h.set(s)
        assertEquals(s, h.get())
        h.clear()
        assertNull(h.get())
    }
}
