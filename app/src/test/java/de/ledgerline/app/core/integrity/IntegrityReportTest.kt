package de.ledgerline.app.core.integrity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityReportTest {
    private fun report(level: AttestationLevel, rooted: Boolean) =
        IntegrityReport(level, rooted, if (rooted) listOf("su binary") else emptyList(), 0L)

    @Test fun strongbox_unrooted_is_clean_and_hardware_backed() {
        val r = report(AttestationLevel.STRONGBOX, rooted = false)
        assertTrue(r.hardwareBacked); assertTrue(r.clean)
    }

    @Test fun tee_is_hardware_backed() {
        assertTrue(report(AttestationLevel.TEE, rooted = false).hardwareBacked)
    }

    @Test fun software_is_not_hardware_backed_nor_clean() {
        val r = report(AttestationLevel.SOFTWARE, rooted = false)
        assertFalse(r.hardwareBacked); assertFalse(r.clean)
    }

    @Test fun unverified_is_not_hardware_backed() {
        assertFalse(report(AttestationLevel.UNVERIFIED, rooted = false).hardwareBacked)
    }

    @Test fun rooted_is_never_clean_even_when_hardware_backed() {
        assertFalse(report(AttestationLevel.STRONGBOX, rooted = true).clean)
    }
}
