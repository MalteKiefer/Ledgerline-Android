package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.CompanyProfile
import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.InvoiceCustomer
import de.ledgerline.app.domain.model.InvoiceLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZugferdXmlTest {

    private val company = CompanyProfile(
        name = "IntellyTec GmbH", address = "Hauptstr. 1\n95326 Kulmbach", vatId = "DE123456789",
        email = "billing@intellytec.de", iban = "DE89 3704 0044 0532 0130 00",
    )
    private val inv = Invoice(
        id = "i1", number = "2026-0001", issueDate = "2026-06-15", dueDate = "2026-06-29", currency = "EUR",
        customer = InvoiceCustomer(name = "ACME GmbH", address = "Weg 2\n10115 Berlin", vatId = "DE987654321"),
        lines = listOf(
            InvoiceLine(desc = "Beratung", qty = 10.0, unit = "Std", unitPrice = 90.0, vatRate = 19.0),
        ),
    )

    @Test fun builds_valid_cii_with_totals() {
        val totals = InvoiceMath.totals(inv)   // 900 net, 171 vat, 1071 gross
        val xml = ZugferdXml.build(inv, company, totals)

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("<rsm:CrossIndustryInvoice"))
        assertTrue(xml.contains("<ram:ID>2026-0001</ram:ID>"))
        assertTrue(xml.contains("<udt:DateTimeString format=\"102\">20260615</udt:DateTimeString>"))
        // Seller + buyer parties, address split.
        assertTrue(xml.contains("<ram:Name>IntellyTec GmbH</ram:Name>"))
        assertTrue(xml.contains("<ram:PostcodeCode>95326</ram:PostcodeCode>"))
        assertTrue(xml.contains("<ram:CityName>Kulmbach</ram:CityName>"))
        assertTrue(xml.contains("<ram:Name>ACME GmbH</ram:Name>"))
        // VAT id + IBAN normalised (no spaces).
        assertTrue(xml.contains("schemeID=\"VA\">DE123456789<"))
        assertTrue(xml.contains("<ram:IBANID>DE89370400440532013000</ram:IBANID>"))
        // Unit code for hours, quantity + line net.
        assertTrue(xml.contains("unitCode=\"HUR\">10.00</ram:BilledQuantity>"))
        assertTrue(xml.contains("<ram:LineTotalAmount>900.00</ram:LineTotalAmount>"))
        // Header monetary summation.
        assertTrue(xml.contains("<ram:TaxTotalAmount currencyID=\"EUR\">171.00</ram:TaxTotalAmount>"))
        assertTrue(xml.contains("<ram:GrandTotalAmount>1071.00</ram:GrandTotalAmount>"))
        assertTrue(xml.contains("<ram:RateApplicablePercent>19.00</ram:RateApplicablePercent>"))
    }

    @Test fun escapes_xml_special_chars() {
        val bad = inv.copy(customer = InvoiceCustomer(name = "A & B <Ltd>"))
        val xml = ZugferdXml.build(bad, company, InvoiceMath.totals(bad))
        assertTrue(xml.contains("A &amp; B &lt;Ltd&gt;"))
        assertFalse(xml.contains("<Ltd>"))
    }

    @Test fun filename_is_safe() {
        assertEquals("2026-0001-factur-x.xml", ZugferdXml.filename(inv))
        assertEquals("rechnung-factur-x.xml", ZugferdXml.filename(inv.copy(number = null)))
    }

    @Test fun unit_code_mapping() {
        assertEquals("HUR", ZugferdXml.unitCode("Stunden"))
        assertEquals("DAY", ZugferdXml.unitCode("Tag"))
        assertEquals("H87", ZugferdXml.unitCode("Stk"))
        assertEquals("C62", ZugferdXml.unitCode(""))
    }
}
