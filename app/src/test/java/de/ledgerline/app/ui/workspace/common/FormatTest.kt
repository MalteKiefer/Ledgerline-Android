package de.ledgerline.app.ui.workspace.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun formats_bytes() {
        assertEquals("0 B", humanSize(0))
        assertEquals("512 B", humanSize(512))
        assertEquals("1.0 KB", humanSize(1024))
        assertEquals("1.5 KB", humanSize(1536))
        assertEquals("1.0 MB", humanSize(1024L * 1024))
        assertEquals("2.0 GB", humanSize(2L * 1024 * 1024 * 1024))
    }
}
