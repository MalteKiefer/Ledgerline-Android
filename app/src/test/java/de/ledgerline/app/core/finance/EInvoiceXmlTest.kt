package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.CompanyProfile
import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.InvoiceCustomer
import de.ledgerline.app.domain.model.InvoiceLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EInvoiceXmlTest {

    @Test fun roundtrips_cii_from_the_exporter() {
        val company = CompanyProfile(name = "IntellyTec GmbH", address = "Hauptstr. 1\n95326 Kulmbach", vatId = "DE123", iban = "DE89370400440532013000")
        val inv = Invoice(
            id = "i1", number = "2026-0001", issueDate = "2026-06-15", dueDate = "2026-06-29", currency = "EUR",
            customer = InvoiceCustomer(name = "ACME GmbH", address = "Weg 2\n10115 Berlin", vatId = "DE987"),
            lines = listOf(InvoiceLine(desc = "Beratung", qty = 10.0, unit = "Std", unitPrice = 90.0, vatRate = 19.0)),
        )
        val xml = ZugferdXml.build(inv, company, InvoiceMath.totals(inv))

        assertTrue(EInvoiceXml.looksLikeEInvoiceXml(xml))
        val p = EInvoiceXml.parse(xml)!!
        assertEquals("cii", p.syntax)
        assertEquals("2026-0001", p.number)
        assertEquals("2026-06-15", p.issueDate)
        assertEquals("2026-06-29", p.dueDate)
        assertEquals("ACME GmbH", p.customer.name)   // buyer party = the invoice customer
        assertEquals("DE987", p.customer.vatId)
        assertEquals(1, p.lines.size)
        assertEquals("Beratung", p.lines[0].desc)
        assertEquals(10.0, p.lines[0].qty, 0.001)
        assertEquals("Std", p.lines[0].unit)
        assertEquals(90.0, p.lines[0].unitPrice, 0.001)
        assertEquals(19.0, p.lines[0].vatRate, 0.001)
        assertEquals(900.0, p.net!!, 0.001)
        assertEquals(171.0, p.vat!!, 0.001)
        assertEquals(1071.0, p.gross!!, 0.001)
        assertEquals(19.0, p.vatRate, 0.001)
    }

    @Test fun parses_minimal_ubl() {
        val ubl = """<?xml version="1.0"?>
<ubl:Invoice xmlns:ubl="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2" xmlns:cbc="x" xmlns:cac="y">
  <cbc:ID>R-42</cbc:ID>
  <cbc:IssueDate>2024-09-02</cbc:IssueDate>
  <cbc:DueDate>2024-09-16</cbc:DueDate>
  <cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
  <cac:AccountingCustomerParty><cac:Party>
    <cac:PartyName><cbc:Name>Globex AG</cbc:Name></cac:PartyName>
    <cac:PostalAddress><cbc:StreetName>Ring 3</cbc:StreetName><cbc:CityName>Hamburg</cbc:CityName><cbc:PostalZone>20095</cbc:PostalZone></cac:PostalAddress>
    <cac:PartyTaxScheme><cbc:CompanyID>DE555</cbc:CompanyID></cac:PartyTaxScheme>
  </cac:Party></cac:AccountingCustomerParty>
  <cac:TaxTotal><cbc:TaxAmount>19.00</cbc:TaxAmount></cac:TaxTotal>
  <cac:LegalMonetaryTotal><cbc:TaxExclusiveAmount>100.00</cbc:TaxExclusiveAmount><cbc:TaxInclusiveAmount>119.00</cbc:TaxInclusiveAmount></cac:LegalMonetaryTotal>
  <cac:InvoiceLine>
    <cbc:InvoicedQuantity unitCode="DAY">2</cbc:InvoicedQuantity>
    <cac:Price><cbc:PriceAmount>50.00</cbc:PriceAmount></cac:Price>
    <cac:Item><cbc:Name>Support</cbc:Name><cac:ClassifiedTaxCategory><cbc:Percent>19</cbc:Percent></cac:ClassifiedTaxCategory></cac:Item>
  </cac:InvoiceLine>
</ubl:Invoice>"""
        val p = EInvoiceXml.parse(ubl)!!
        assertEquals("ubl", p.syntax)
        assertEquals("R-42", p.number)
        assertEquals("2024-09-02", p.issueDate)
        assertEquals("Globex AG", p.customer.name)
        assertEquals("20095 Hamburg", p.customer.address.substringAfter("\n"))
        assertEquals("DE555", p.customer.vatId)
        assertEquals("Support", p.lines[0].desc)
        assertEquals(2.0, p.lines[0].qty, 0.001)
        assertEquals("Tage", p.lines[0].unit)
        assertEquals(50.0, p.lines[0].unitPrice, 0.001)
        assertEquals(100.0, p.net!!, 0.001)
        assertEquals(119.0, p.gross!!, 0.001)
    }

    @Test fun rejects_non_einvoice() {
        assertFalse(EInvoiceXml.looksLikeEInvoiceXml("<html>hi</html>"))
        assertNull(EInvoiceXml.parse("<html>hi</html>"))
        assertNull(EInvoiceXml.parse("<rsm:CrossIndustryInvoice></rsm:CrossIndustryInvoice>")) // no number → null
    }
}
