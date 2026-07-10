package de.ledgerline.app.ui.workspace.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatDueTest {
    @Test fun blank_is_empty() { assertEquals("", formatDue("")) }
    @Test fun date_only_parses() { assertTrue(formatDue("2026-07-15").isNotBlank()) }
    @Test fun datetime_parses() { assertTrue(formatDue("2026-07-15T14:30:00Z").isNotBlank()) }
    @Test fun garbage_falls_back_to_raw() { assertEquals("not-a-date", formatDue("not-a-date")) }
}
