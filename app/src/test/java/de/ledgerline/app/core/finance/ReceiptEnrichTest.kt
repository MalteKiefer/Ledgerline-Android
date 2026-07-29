package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.Partner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptEnrichTest {

    @Test fun builds_a_receipt_name() {
        assertEquals(
            "20260615; netcup GmbH; Rechnung R-42.pdf",
            ReceiptEnrich.buildReceiptName(date = "2026-06-15", partner = "netcup GmbH", number = "R-42", ext = "pdf"),
        )
        assertEquals(
            "20260615; ACME; Beleg",
            ReceiptEnrich.buildReceiptName(date = "2026-06-15", partner = "ACME", number = ""),
        )
        // ';' and slashes in the partner are sanitised, ext gets a leading dot.
        assertEquals(
            "Beleg.jpg",
            ReceiptEnrich.buildReceiptName(date = "", partner = "", number = "", ext = ".jpg"),
        )
    }

    @Test fun normalises_merchant_names() {
        assertEquals("netcup", ReceiptEnrich.normMerchant("netcup GmbH"))
        assertEquals("netcup", ReceiptEnrich.normMerchant("NETCUP  Deutschland"))
        assertEquals(ReceiptEnrich.normMerchant("Acme Ltd"), ReceiptEnrich.normMerchant("ACME  limited"))
    }

    @Test fun matches_partner_and_learned_category() {
        val partners = listOf(
            Partner(id = "p1", name = "netcup GmbH", category = "Software"),
            Partner(id = "p2", name = "Shell AG", category = "Kfz"),
        )
        assertEquals("p1", ReceiptEnrich.matchPartner(partners, "netcup")?.id)
        assertEquals("Software", ReceiptEnrich.learnedCategoryFor(partners, "NETCUP Deutschland"))
        assertNull(ReceiptEnrich.matchPartner(partners, "Unknown Co"))
        assertEquals("", ReceiptEnrich.learnedCategoryFor(partners, "Unknown Co"))
    }
}
