package de.ledgerline.app.core.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptOcrTest {

    private val sample = """
        IntellyTec GmbH
        Industriestr. 25, 95326 Kulmbach
        Rechnung Nr: 2026-0042
        Rechnungsdatum: 15.06.2026

        Position           Menge   Betrag
        Beratung             10    900,00 €
        Zwischensumme              900,00 €
        MwSt 19%                   171,00 €
        Gesamtbetrag               1.071,00 €
    """.trimIndent()

    @Test fun total_prefers_labelled_gross_over_net_and_tax() {
        assertEquals(1071.0, ReceiptOcr.extractTotal(sample)!!, 0.001)
    }

    @Test fun date_is_german_day_first() {
        assertEquals("2026-06-15", ReceiptOcr.extractDate(sample))
    }

    @Test fun merchant_is_the_company_legal_form_line() {
        assertEquals("IntellyTec GmbH", ReceiptOcr.extractMerchant(sample))
    }

    @Test fun number_after_an_explicit_label() {
        assertEquals("2026-0042", ReceiptOcr.extractNumber(sample))
    }

    @Test fun vat_rate_is_highest_explicit() {
        assertEquals("19", ReceiptOcr.extractVatRate(sample))
        assertEquals("0", ReceiptOcr.extractVatRate("Kleinunternehmer gemäß § 19 UStG"))
    }

    @Test fun currency_detection() {
        assertEquals("EUR", ReceiptOcr.extractCurrency(sample))
        assertEquals("USD", ReceiptOcr.extractCurrency("Total: US$ 12.00"))
        assertEquals("CHF", ReceiptOcr.extractCurrency("Betrag CHF 20.00"))
    }

    @Test fun analyze_bundles_fields_and_tags() {
        val a = ReceiptOcr.analyze("Adobe Systems Software Inc\nSubscription\nInvoice No: A123\nTotal 59,99 €\n01.03.2026\nMwSt 19%")
        assertEquals("Software", a.category)
        assertEquals(59.99, a.total!!, 0.001)
        assertEquals("2026-03-01", a.date)
        assertTrue(a.tags.contains("Software"))
    }

    @Test fun brand_fallback_when_no_legal_form() {
        assertEquals("Amazon", ReceiptOcr.extractMerchant("Your order from amazon.de\nItem 1\nTotal 9,99"))
    }
}
