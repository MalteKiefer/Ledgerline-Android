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

    @Test
    fun union_sorts_dedupes_case_insensitively_and_drops_blanks() {
        val result = Tags.union(listOf(listOf("Work", " urgent "), listOf("work", "", "Home")))
        // Case-insensitive distinct (first-seen casing), sorted case-insensitively.
        assertEquals(listOf("Home", "urgent", "Work"), result)
    }

    @Test
    fun union_empty_input_is_empty() {
        assertEquals(emptyList<String>(), Tags.union(emptyList()))
        assertEquals(emptyList<String>(), Tags.union(listOf(emptyList(), listOf(" "))))
    }

    @Test
    fun contains_matches_case_insensitively() {
        assertEquals(true, Tags.contains(listOf("Work", "Home"), "work"))
        assertEquals(false, Tags.contains(listOf("Work"), "wor"))   // exact, not substring
    }
}
