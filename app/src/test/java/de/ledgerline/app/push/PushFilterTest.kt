package de.ledgerline.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushFilterTest {

    @Test fun parses_full_payload() {
        val p = PushFilter.parse(
            """{"id":42,"category":"invoice","level":"warning","title":"Overdue","body":"Invoice #7"}""".toByteArray(),
        )!!
        assertEquals(42L, p.id)
        assertEquals("invoice", p.category)
        assertEquals("warning", p.level)
        assertEquals("Overdue", p.title)
        assertEquals("Invoice #7", p.body)
    }

    @Test fun tolerates_unknown_keys_and_missing_fields() {
        val p = PushFilter.parse("""{"title":"Hi","extra":"ignored"}""".toByteArray())!!
        assertEquals("Hi", p.title)
        assertEquals(0L, p.id)
        assertEquals("info", p.level)   // default
        assertNull(p.body)
    }

    @Test fun rejects_garbage() {
        assertNull(PushFilter.parse("not json".toByteArray()))
        assertNull(PushFilter.parse(ByteArray(0)))
    }

    @Test fun disabled_never_shows() {
        assertFalse(PushFilter.shouldShow(enabled = false, muted = emptySet(), category = "invoice"))
    }

    @Test fun muted_category_is_hidden_others_shown() {
        val muted = setOf("invoice")
        assertFalse(PushFilter.shouldShow(true, muted, "invoice"))
        assertTrue(PushFilter.shouldShow(true, muted, "task"))
    }

    @Test fun blank_category_always_shows_when_enabled() {
        assertTrue(PushFilter.shouldShow(true, setOf("invoice"), ""))
    }
}
