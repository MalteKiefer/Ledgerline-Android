package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.CompanyProfile
import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.InvoiceLine
import de.ledgerline.app.domain.model.InvoiceTotals
import java.util.Locale
import kotlin.math.roundToLong

/**
 * ZUGFeRD / Factur-X invoice XML (UN/CEFACT Cross Industry Invoice, EN 16931 profile) — a port of
 * the web `shared/zugferd.js`. Pure + client-side (ZK: the invoice never leaves the device). Produces
 * the structured CII XML that is the heart of a ZUGFeRD e-invoice — directly usable as an
 * XRechnung/Factur-X XML and ready to be embedded in a PDF/A-3 later.
 */
object ZugferdXml {

    private fun esc(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("'", "&apos;").replace("\"", "&quot;")

    private fun dec(n: Double): String = String.format(Locale.ROOT, "%.2f", (n * 100.0).roundToLong() / 100.0)

    /** ISO yyyy-mm-dd → CII format-102 date "yyyymmdd". */
    private fun ciiDate(iso: String?): String =
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(iso ?: "")?.let { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] } ?: ""

    data class Address(val line: String, val postcode: String, val city: String, val country: String)

    /** German address block → { line, postcode, city, country }. */
    fun splitAddress(address: String?): Address {
        val lines = (address ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        var postcode = ""; var city = ""
        val streetParts = mutableListOf<String>()
        for (ln in lines) {
            val m = Regex("^(?:D-)?(\\d{5})\\s+(.+)$").find(ln)
            if (m != null && postcode.isEmpty()) { postcode = m.groupValues[1]; city = m.groupValues[2] } else streetParts.add(ln)
        }
        return Address(streetParts.joinToString(", "), postcode, city, "DE")
    }

    /** UN/ECE Rec 20 unit code for a free-text unit. Default C62 (one/piece). */
    fun unitCode(unit: String?): String {
        val u = (unit ?: "").lowercase()
        return when {
            Regex("std|stunde|hour|\\bh\\b").containsMatchIn(u) -> "HUR"
            Regex("monat|month|\\bmon\\b").containsMatchIn(u) -> "MON"
            Regex("tag|day").containsMatchIn(u) -> "DAY"
            Regex("stück|stk|piece|pcs").containsMatchIn(u) -> "H87"
            else -> "C62"
        }
    }

    private fun partyXml(role: String, name: String?, addr: String?, vatId: String?, email: String?): String {
        val a = splitAddress(addr)
        val sb = StringBuilder()
        sb.append("      <ram:${role}TradeParty>\n")
        sb.append("        <ram:Name>${esc(name)}</ram:Name>\n")
        sb.append("        <ram:PostalTradeAddress>\n")
        if (a.postcode.isNotEmpty()) sb.append("          <ram:PostcodeCode>${esc(a.postcode)}</ram:PostcodeCode>\n")
        if (a.line.isNotEmpty()) sb.append("          <ram:LineOne>${esc(a.line)}</ram:LineOne>\n")
        if (a.city.isNotEmpty()) sb.append("          <ram:CityName>${esc(a.city)}</ram:CityName>\n")
        sb.append("          <ram:CountryID>${esc(a.country)}</ram:CountryID>\n")
        sb.append("        </ram:PostalTradeAddress>\n")
        if (!email.isNullOrEmpty()) sb.append("        <ram:URIUniversalCommunication><ram:URIID schemeID=\"EM\">${esc(email)}</ram:URIID></ram:URIUniversalCommunication>\n")
        if (!vatId.isNullOrEmpty()) sb.append("        <ram:SpecifiedTaxRegistration><ram:ID schemeID=\"VA\">${esc(vatId.replace(Regex("\\s+"), ""))}</ram:ID></ram:SpecifiedTaxRegistration>\n")
        sb.append("      </ram:${role}TradeParty>")
        return sb.toString()
    }

    /** Build the CII XML for [inv] with [company] as the seller and precomputed [totals]. */
    fun build(inv: Invoice, company: CompanyProfile, totals: InvoiceTotals): String {
        val cur = inv.currency.ifBlank { "EUR" }

        val lines = inv.lines.mapIndexed { i, l -> lineXml(i, l) }.joinToString("\n")

        val taxes = if (totals.vatByRate.isEmpty()) {
            "      <ram:ApplicableTradeTax><ram:CalculatedAmount>0.00</ram:CalculatedAmount><ram:TypeCode>VAT</ram:TypeCode><ram:BasisAmount>${dec(totals.net)}</ram:BasisAmount><ram:CategoryCode>E</ram:CategoryCode><ram:RateApplicablePercent>0.00</ram:RateApplicablePercent></ram:ApplicableTradeTax>"
        } else {
            totals.vatByRate.entries.sortedBy { it.key }.joinToString("\n") { (r, vat) ->
                val basis = if (r > 0) (vat / (r / 100.0) * 100.0).roundToLong() / 100.0 else totals.net
                val cat = if (r > 0) "S" else "E"
                val exemption = if (r > 0) "" else "\n        <ram:ExemptionReason>Kleinunternehmer gemäß § 19 UStG</ram:ExemptionReason>"
                "      <ram:ApplicableTradeTax>\n" +
                    "        <ram:CalculatedAmount>${dec(vat)}</ram:CalculatedAmount>\n" +
                    "        <ram:TypeCode>VAT</ram:TypeCode>$exemption\n" +
                    "        <ram:BasisAmount>${dec(basis)}</ram:BasisAmount>\n" +
                    "        <ram:CategoryCode>$cat</ram:CategoryCode>\n" +
                    "        <ram:RateApplicablePercent>${dec(r)}</ram:RateApplicablePercent>\n" +
                    "      </ram:ApplicableTradeTax>"
            }
        }

        val paymentMeans = if (company.iban.isNotBlank())
            "      <ram:SpecifiedTradeSettlementPaymentMeans><ram:TypeCode>58</ram:TypeCode><ram:PayeePartyCreditorFinancialAccount><ram:IBANID>${esc(company.iban.replace(Regex("\\s+"), ""))}</ram:IBANID></ram:PayeePartyCreditorFinancialAccount></ram:SpecifiedTradeSettlementPaymentMeans>\n"
        else ""
        val terms = if (inv.dueDate.isNotBlank())
            "      <ram:SpecifiedTradePaymentTerms><ram:DueDateDateTime><udt:DateTimeString format=\"102\">${ciiDate(inv.dueDate)}</udt:DateTimeString></ram:DueDateDateTime></ram:SpecifiedTradePaymentTerms>\n"
        else ""

        return """<?xml version="1.0" encoding="UTF-8"?>
<rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100" xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100" xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100">
  <rsm:ExchangedDocumentContext>
    <ram:GuidelineSpecifiedDocumentContextParameter><ram:ID>urn:cen.eu:en16931:2017</ram:ID></ram:GuidelineSpecifiedDocumentContextParameter>
  </rsm:ExchangedDocumentContext>
  <rsm:ExchangedDocument>
    <ram:ID>${esc(inv.number ?: "")}</ram:ID>
    <ram:TypeCode>380</ram:TypeCode>
    <ram:IssueDateTime><udt:DateTimeString format="102">${ciiDate(inv.issueDate)}</udt:DateTimeString></ram:IssueDateTime>
  </rsm:ExchangedDocument>
  <rsm:SupplyChainTradeTransaction>
$lines
    <ram:ApplicableHeaderTradeAgreement>
${partyXml("Seller", company.name, company.address, company.vatId, company.email)}
${partyXml("Buyer", inv.customer.name, inv.customer.address, inv.customer.vatId, inv.customer.email)}
    </ram:ApplicableHeaderTradeAgreement>
    <ram:ApplicableHeaderTradeDelivery>
      <ram:ActualDeliverySupplyChainEvent><ram:OccurrenceDateTime><udt:DateTimeString format="102">${ciiDate(inv.issueDate)}</udt:DateTimeString></ram:OccurrenceDateTime></ram:ActualDeliverySupplyChainEvent>
    </ram:ApplicableHeaderTradeDelivery>
    <ram:ApplicableHeaderTradeSettlement>
      <ram:InvoiceCurrencyCode>${esc(cur)}</ram:InvoiceCurrencyCode>
$paymentMeans$taxes
$terms      <ram:SpecifiedTradeSettlementHeaderMonetarySummation>
        <ram:LineTotalAmount>${dec(totals.net)}</ram:LineTotalAmount>
        <ram:TaxBasisTotalAmount>${dec(totals.net)}</ram:TaxBasisTotalAmount>
        <ram:TaxTotalAmount currencyID="${esc(cur)}">${dec(totals.vat)}</ram:TaxTotalAmount>
        <ram:GrandTotalAmount>${dec(totals.gross)}</ram:GrandTotalAmount>
        <ram:DuePayableAmount>${dec(totals.gross)}</ram:DuePayableAmount>
      </ram:SpecifiedTradeSettlementHeaderMonetarySummation>
    </ram:ApplicableHeaderTradeSettlement>
  </rsm:SupplyChainTradeTransaction>
</rsm:CrossIndustryInvoice>
"""
    }

    private fun lineXml(i: Int, l: InvoiceLine): String {
        val lineNet = (l.qty * l.unitPrice * 100.0).roundToLong() / 100.0
        val cat = if (l.vatRate > 0) "S" else "E"
        return "    <ram:IncludedSupplyChainTradeLineItem>\n" +
            "      <ram:AssociatedDocumentLineDocument><ram:LineID>${i + 1}</ram:LineID></ram:AssociatedDocumentLineDocument>\n" +
            "      <ram:SpecifiedTradeProduct><ram:Name>${esc(l.desc.ifBlank { "-" })}</ram:Name></ram:SpecifiedTradeProduct>\n" +
            "      <ram:SpecifiedLineTradeAgreement><ram:NetPriceProductTradePrice><ram:ChargeAmount>${dec(l.unitPrice)}</ram:ChargeAmount></ram:NetPriceProductTradePrice></ram:SpecifiedLineTradeAgreement>\n" +
            "      <ram:SpecifiedLineTradeDelivery><ram:BilledQuantity unitCode=\"${unitCode(l.unit)}\">${dec(l.qty)}</ram:BilledQuantity></ram:SpecifiedLineTradeDelivery>\n" +
            "      <ram:SpecifiedLineTradeSettlement>\n" +
            "        <ram:ApplicableTradeTax><ram:TypeCode>VAT</ram:TypeCode><ram:CategoryCode>$cat</ram:CategoryCode><ram:RateApplicablePercent>${dec(l.vatRate)}</ram:RateApplicablePercent></ram:ApplicableTradeTax>\n" +
            "        <ram:SpecifiedTradeSettlementLineMonetarySummation><ram:LineTotalAmount>${dec(lineNet)}</ram:LineTotalAmount></ram:SpecifiedTradeSettlementLineMonetarySummation>\n" +
            "      </ram:SpecifiedLineTradeSettlement>\n" +
            "    </ram:IncludedSupplyChainTradeLineItem>"
    }

    /** A filesystem-safe XML filename for the invoice. */
    fun filename(inv: Invoice): String {
        val base = (inv.number ?: "rechnung").replace(Regex("[^\\w.-]+"), "_")
        return "$base-factur-x.xml"
    }
}
