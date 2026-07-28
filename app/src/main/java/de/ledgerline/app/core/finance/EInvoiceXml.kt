package de.ledgerline.app.core.finance

/**
 * Parse an embedded e-invoice XML (ZUGFeRD/Factur-X CII or XRechnung UBL) into an invoice draft — a
 * port of the web `shared/einvoice-xml.js`. Namespace-agnostic regex extraction keeps it pure and
 * tolerant of prefixes; reading the structured XML is far more reliable than scraping rendered text.
 */
object EInvoiceXml {

    data class ParsedLine(val desc: String, val qty: Double, val unit: String, val unitPrice: Double, val vatRate: Double)
    data class ParsedCustomer(val name: String, val address: String, val vatId: String, val email: String)
    data class ParsedEInvoice(
        val syntax: String,
        val number: String?,
        val issueDate: String?,
        val dueDate: String?,
        val currency: String,
        val customer: ParsedCustomer,
        val lines: List<ParsedLine>,
        val net: Double?,
        val vat: Double?,
        val gross: Double?,
        val vatRate: Double,
    )

    private fun num(s: String?): Double? {
        if (s == null) return null
        val cleaned = s.replace(Regex("[^\\d.,-]"), "").replace(Regex(",(?=\\d{3}\\b)"), "").replace(",", ".")
        return cleaned.toDoubleOrNull()
    }

    private fun tagRegex(name: String): Regex =
        Regex("<(?:\\w+:)?" + name + "\\b[^>]*>([\\s\\S]*?)</(?:\\w+:)?" + name + ">", RegexOption.IGNORE_CASE)

    /** First inner text of a namespace-agnostic tag, markup stripped + entities unescaped. */
    private fun tagText(xml: String?, name: String): String? {
        val m = tagRegex(name).find(xml ?: "") ?: return null
        return m.groupValues[1]
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ").trim()
    }

    /** All inner blocks (with markup) of a namespace-agnostic tag, in order. */
    private fun blocks(xml: String?, name: String): List<String> =
        tagRegex(name).findAll(xml ?: "").map { it.groupValues[1] }.toList()

    /** CII "102" date (yyyymmdd) or UBL "yyyy-mm-dd" → ISO yyyy-mm-dd. */
    private fun isoDate(s: String?): String? {
        if (s == null) return null
        Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(s)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
        Regex("(\\d{4})(\\d{2})(\\d{2})").find(s)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
        return null
    }

    private val UNIT = mapOf("HUR" to "Std", "DAY" to "Tage", "MON" to "Monate", "H87" to "Stk", "C62" to "", "EA" to "", "ANN" to "Jahre")
    private fun unitLabel(code: String?): String = UNIT[(code ?: "").uppercase()] ?: ""

    private fun ciiUnit(itemXml: String): String =
        unitLabel(Regex("<(?:\\w+:)?BilledQuantity\\b[^>]*unitCode=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(itemXml)?.groupValues?.get(1))

    private fun ublUnit(lineXml: String): String =
        unitLabel(Regex("<(?:\\w+:)?InvoicedQuantity\\b[^>]*unitCode=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(lineXml)?.groupValues?.get(1))

    private fun parseCII(xml: String): ParsedEInvoice {
        val doc = blocks(xml, "ExchangedDocument").firstOrNull() ?: ""
        val buyer = blocks(xml, "BuyerTradeParty").firstOrNull() ?: ""
        val settle = blocks(xml, "ApplicableHeaderTradeSettlement").firstOrNull() ?: xml
        val sum = blocks(settle, "SpecifiedTradeSettlementHeaderMonetarySummation").firstOrNull() ?: settle

        val lines = blocks(xml, "IncludedSupplyChainTradeLineItem").map { li ->
            val priceBlk = blocks(li, "NetPriceProductTradePrice").firstOrNull() ?: li
            ParsedLine(
                desc = tagText(blocks(li, "SpecifiedTradeProduct").firstOrNull() ?: li, "Name") ?: "",
                qty = num(tagText(li, "BilledQuantity")) ?: 1.0,
                unit = ciiUnit(li),
                unitPrice = num(tagText(priceBlk, "ChargeAmount")) ?: 0.0,
                vatRate = num(tagText(li, "RateApplicablePercent")) ?: 0.0,
            )
        }
        val buyerVat = Regex("schemeID=\"VA\"[^>]*>([^<]*)", RegexOption.IGNORE_CASE).find(buyer)?.groupValues?.get(1)
        return ParsedEInvoice(
            syntax = "cii",
            number = tagText(doc, "ID"),
            issueDate = isoDate(tagText(doc, "DateTimeString")),
            dueDate = isoDate(tagText(blocks(settle, "SpecifiedTradePaymentTerms").firstOrNull() ?: "", "DateTimeString")),
            currency = tagText(settle, "InvoiceCurrencyCode") ?: "EUR",
            customer = buyerAddress(buyer, tagText(buyer, "Name"), buyerVat, "cii"),
            lines = lines,
            net = num(tagText(sum, "LineTotalAmount")),
            vat = num(tagText(sum, "TaxTotalAmount")),
            gross = num(tagText(sum, "GrandTotalAmount")),
            vatRate = 0.0,
        )
    }

    private fun parseUBL(xml: String): ParsedEInvoice {
        val cust = blocks(xml, "AccountingCustomerParty").firstOrNull() ?: ""
        val party = blocks(cust, "Party").firstOrNull() ?: cust
        val totals = blocks(xml, "LegalMonetaryTotal").firstOrNull() ?: ""
        val taxTotal = blocks(xml, "TaxTotal").firstOrNull() ?: ""

        val lines = blocks(xml, "InvoiceLine").map { li ->
            val item = blocks(li, "Item").firstOrNull() ?: li
            ParsedLine(
                desc = tagText(item, "Name") ?: "",
                qty = num(tagText(li, "InvoicedQuantity")) ?: 1.0,
                unit = ublUnit(li),
                unitPrice = num(tagText(blocks(li, "Price").firstOrNull() ?: li, "PriceAmount")) ?: 0.0,
                vatRate = num(tagText(blocks(item, "ClassifiedTaxCategory").firstOrNull() ?: item, "Percent")) ?: 0.0,
            )
        }
        val name = tagText(blocks(party, "PartyName").firstOrNull() ?: party, "Name")
            ?: tagText(blocks(party, "PartyLegalEntity").firstOrNull() ?: party, "RegistrationName")
        val net = num(tagText(totals, "TaxExclusiveAmount")) ?: num(tagText(totals, "LineExtensionAmount"))
        val gross = num(tagText(totals, "TaxInclusiveAmount")) ?: num(tagText(totals, "PayableAmount"))
        val number = Regex("<(?:\\w+:)?ID\\b[^>]*>([^<]*)<", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
        return ParsedEInvoice(
            syntax = "ubl",
            number = number,
            issueDate = isoDate(tagText(xml, "IssueDate")),
            dueDate = isoDate(tagText(xml, "DueDate")),
            currency = tagText(xml, "DocumentCurrencyCode") ?: "EUR",
            customer = buyerAddress(party, name, tagText(blocks(party, "PartyTaxScheme").firstOrNull() ?: "", "CompanyID"), "ubl"),
            lines = lines,
            net = net,
            vat = num(tagText(taxTotal, "TaxAmount")),
            gross = gross,
            vatRate = 0.0,
        )
    }

    private fun buyerAddress(partyXml: String, name: String?, vatId: String?, syntax: String): ParsedCustomer {
        val addr = blocks(partyXml, if (syntax == "cii") "PostalTradeAddress" else "PostalAddress").firstOrNull() ?: ""
        val street = tagText(addr, if (syntax == "cii") "LineOne" else "StreetName") ?: ""
        val city = tagText(addr, "CityName") ?: ""
        val zip = tagText(addr, if (syntax == "cii") "PostcodeCode" else "PostalZone") ?: ""
        val cityLine = if (zip.isNotEmpty() && !Regex("\\d{4,}").containsMatchIn(city)) "$zip $city".trim() else city
        val email = tagText(partyXml, "ElectronicMail") ?: tagText(partyXml, "URIID") ?: ""
        return ParsedCustomer(
            name = name ?: "",
            address = listOf(street, cityLine).filter { it.isNotEmpty() }.joinToString("\n"),
            vatId = vatId ?: "",
            email = email,
        )
    }

    /** True if the text looks like an embedded e-invoice XML worth parsing. */
    fun looksLikeEInvoiceXml(text: String?): Boolean =
        Regex("CrossIndustryInvoice|:Invoice-2|ubl:schema", RegexOption.IGNORE_CASE).containsMatchIn(text ?: "")

    /** Parse an e-invoice XML string → a normalised draft, or null if not a recognisable CII/UBL invoice. */
    fun parse(xml: String?): ParsedEInvoice? {
        val s = xml ?: ""
        var out = when {
            Regex("CrossIndustryInvoice", RegexOption.IGNORE_CASE).containsMatchIn(s) -> parseCII(s)
            Regex(":Invoice-2|<Invoice\\b|ubl:schema", RegexOption.IGNORE_CASE).containsMatchIn(s) -> parseUBL(s)
            else -> null
        } ?: return null
        if (out.number.isNullOrEmpty()) return null
        var net = out.net; var gross = out.gross
        if (net == null && gross != null && out.vat != null) net = gross - out.vat!!
        if (gross == null && net != null) gross = net + (out.vat ?: 0.0)
        out = out.copy(net = net, gross = gross, vatRate = if (out.lines.isNotEmpty()) out.lines[0].vatRate else 0.0)
        return out
    }
}
