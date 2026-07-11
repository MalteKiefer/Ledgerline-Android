package de.ledgerline.app.domain.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class TagsTest {

    @Test
    fun parseTags_trims_and_drops_blanks() {
        assertEquals(listOf("a", "b", "c"), Tags.parseTags("a, b ,,c"))
    }

    @Test
    fun parseTags_blank_input_is_empty() {
        assertEquals(emptyList<String>(), Tags.parseTags(""))
        assertEquals(emptyList<String>(), Tags.parseTags("   "))
        assertEquals(emptyList<String>(), Tags.parseTags(", ,"))
    }

    @Test
    fun formatTags_joins_with_comma_space() {
        assertEquals("a, b, c", Tags.formatTags(listOf("a", "b", "c")))
        assertEquals("", Tags.formatTags(emptyList()))
    }
}
