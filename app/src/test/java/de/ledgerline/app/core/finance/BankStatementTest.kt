package de.ledgerline.app.core.finance

import de.ledgerline.app.core.finance.BankStatement.ParsedTx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the web `__tests__/bank-statement.test.js` fixtures + expectations for byte-parity. */
class BankStatementTest {

    @Test fun parses_german_and_english_amounts() {
        assertEquals(1992.43, BankStatement.parseAmount("1.992,43")!!, 0.0)
        assertEquals(-175.28, BankStatement.parseAmount("-175,28")!!, 0.0)
        assertEquals(213.51, BankStatement.parseAmount("213,51")!!, 0.0)
        assertEquals(1992.43, BankStatement.parseAmount("1,992.43")!!, 0.0)
        assertEquals(150.0, BankStatement.parseAmount("150.00")!!, 0.0)
        assertEquals(2200.0, BankStatement.parseAmount("2200,00")!!, 0.0)
        assertNull(BankStatement.parseAmount(""))
    }

    @Test fun normalises_dates() {
        assertEquals("2026-07-29", BankStatement.parseDate("29.07.26"))
        assertEquals("2024-08-26", BankStatement.parseDate("2024-08-26"))
        assertEquals("2026-04-30", BankStatement.parseDate("260430"))
        assertNull(BankStatement.parseDate(""))
    }

    @Test fun detects_the_format() {
        assertEquals("mt940", BankStatement.detectFormat(":20:X\n:25:Y\n:61:2604300430DR1,00N010NONREF"))
        assertEquals("csv", BankStatement.detectFormat("\"a\";\"b\";\"c\"\n\"1\";\"2\";\"3\""))
        assertEquals("unknown", BankStatement.detectFormat("just some prose"))
    }

    private val MT940 = listOf(
        ":20:STARTUMSE",
        ":25:77150000/0101918910",
        ":28C:00000/001",
        ":60F:C260429EUR1992,43",
        ":61:2604300430DR213,51N010NONREF",
        ":86:601?00EINZUG RATE?106000?20Rechnung?21 Darlehen?30BYLADEM1KUB?31DE69771500006202469687?32Sparkas",
        "se Kulmbach",
        ":61:2607280728CR2200,00N060NONREF",
        ":86:152?00GUTSCHRIFT?20SVWZ+Miete Juli?32Max Muster",
        ":62F:C260728EUR3978,92",
        "-",
    ).joinToString("\n")

    @Test fun parses_mt940() {
        val r = BankStatement.parseMt940(MT940)
        assertEquals(1992.43, r.openingBalance!!, 0.0)
        assertEquals(3978.92, r.closingBalance!!, 0.0)
        assertEquals("EUR", r.currency)
        assertEquals(2, r.transactions.size)
        val a = r.transactions[0]; val b = r.transactions[1]
        assertEquals(-213.51, a.amount, 0.0)
        assertEquals("Sparkasse Kulmbach", a.counterparty)
        assertEquals("DE69771500006202469687", a.iban)
        assertEquals("BYLADEM1KUB", a.bic)
        assertEquals(2200.0, b.amount, 0.0)
        assertEquals("Miete Juli", b.purpose)
        val sum = r.transactions.sumOf { it.amount }
        assertEquals(r.closingBalance!!, Math.round((r.openingBalance!! + sum) * 100.0) / 100.0, 0.0)
    }

    @Test fun splits_field86() {
        val f = BankStatement.parseMt940Field86("152?00GUTSCHRIFT?20SVWZ+Hello?32Jane?33 Doe?31DE12?30ABCDEF")
        assertEquals("GUTSCHRIFT", f.bookingText)
        assertEquals("Jane Doe", f.counterparty)
        assertEquals("DE12", f.iban)
        assertEquals("ABCDEF", f.bic)
    }

    private val SPK = listOf(
        "\"Auftragskonto\";\"Buchungstag\";\"Valutadatum\";\"Buchungstext\";\"Verwendungszweck\";\"Beguenstigter/Zahlungspflichtiger\";\"Kontonummer\";\"BLZ\";\"Betrag\";\"Waehrung\";\"Info\"",
        "\"DE10\";\"28.07.26\";\"28.07.26\";\"GUTSCHR\";\"SVWZ+Gehalt\";\"ACME GmbH\";\"DE99\";\"BYLADEM1KUB\";\"2200,00\";\"EUR\";\"Umsatz gebucht\"",
        "\"DE10\";\"\";\"29.07.26\";\"LASTSCHRIFT\";\"SVWZ+vorgemerkt\";\"Shop\";\"DE88\";\"COBADEFF\";\"-10,00\";\"EUR\";\"vorgemerkt\"",
    ).joinToString("\n")

    @Test fun auto_maps_sparkasse_csv() {
        val c = BankStatement.parseCsv(SPK)
        assertEquals(';', c.delimiter)
        val m = BankStatement.detectCsvMapping(c.header)!!
        assertEquals("sparkasse", m.name)
        val (txns, skipped) = BankStatement.applyCsvMapping(c.header, c.rows, m.map)
        assertEquals(1, skipped)
        assertEquals(1, txns.size)
        assertEquals("2026-07-28", txns[0].date)
        assertEquals(2200.0, txns[0].amount, 0.0)
        assertEquals("ACME GmbH", txns[0].counterparty)
        assertEquals("Gehalt", txns[0].purpose)
    }

    private val GEN = listOf(
        "\"Buchungsdatum\";\"Wertstellungsdatum\";\"Transaktionstyp\";\"Empfänger\";\"Betrag\";\"IBAN\";\"Verwendungszweck\";\"end_to_end_id\";\"Buchungsstatus\";\"Kategorie\";\"Persönliche Notiz\"",
        "\"2024-09-02\";\"2024-09-02\";\"Überweisung\";\"IntellyTec GmbH\";\"866,34\";\"DE91\";\"Rechnung 1\";\"E-abc\";\"Gebucht\";\"USt 19%\";\"\"",
    ).joinToString("\n")

    @Test fun auto_maps_generic_iso_csv() {
        val c = BankStatement.parseCsv(GEN)
        val m = BankStatement.detectCsvMapping(c.header)!!
        assertEquals("generic-iso", m.name)
        val (txns, _) = BankStatement.applyCsvMapping(c.header, c.rows, m.map)
        assertEquals("2024-09-02", txns[0].date)
        assertEquals(866.34, txns[0].amount, 0.0)
        assertEquals("IntellyTec GmbH", txns[0].counterparty)
        assertEquals("DE91", txns[0].iban)
        assertEquals("E-abc", txns[0].eref)
    }

    @Test fun unknown_csv_needs_manual_mapping() {
        val c = BankStatement.parseCsv("\"When\";\"How much\";\"Who\"\n\"2024-01-01\";\"5,00\";\"X\"")
        assertNull(BankStatement.detectCsvMapping(c.header))
        val (txns, _) = BankStatement.applyCsvMapping(c.header, c.rows, mapOf("date" to "When", "amount" to "How much", "counterparty" to "Who"))
        assertEquals("2024-01-01", txns[0].date)
        assertEquals(5.0, txns[0].amount, 0.0)
        assertEquals("X", txns[0].counterparty)
        assertTrue("amount" in BankStatement.TX_FIELDS)
    }

    @Test fun dedupes_on_reimport() {
        val a = ParsedTx(date = "2024-01-01", amount = -5.0, counterparty = "X", purpose = "p")
        val b = ParsedTx(date = "2024-01-02", amount = -6.0, counterparty = "Y", purpose = "q", eref = "E1")
        assertTrue(BankStatement.txSignature(b).contains("E1"))
        assertEquals(listOf(b), BankStatement.dedupeTransactions(listOf(a), listOf(a, b)))
        assertEquals(1, BankStatement.dedupeTransactions(emptyList(), listOf(a, a)).size)
    }

    @Test fun enriches_existing_on_reimport() {
        val existing = listOf(ParsedTx(date = "2024-01-02", amount = -6.0, counterparty = "Y", purpose = "q", eref = "E1"))
        val incoming = listOf(
            ParsedTx(date = "2024-01-02", amount = -6.0, counterparty = "Y", purpose = "q", eref = "E1", iban = "DE99", bic = "ABCDEF"),
            ParsedTx(date = "2024-01-03", amount = -7.0, counterparty = "Z", purpose = "r"),
        )
        val (fresh, updates) = BankStatement.enrichExisting(existing, incoming)
        assertEquals(1, fresh.size)
        assertEquals(-7.0, fresh[0].amount, 0.0)
        assertEquals(1, updates.size)
        assertEquals(mapOf("iban" to "DE99", "bic" to "ABCDEF"), updates[0].patch)
    }

    @Test fun does_not_overwrite_present_fields() {
        val existing = listOf(ParsedTx(date = "2024-01-02", amount = -6.0, eref = "E1", iban = "DE-OLD"))
        val incoming = listOf(ParsedTx(date = "2024-01-02", amount = -6.0, eref = "E1", iban = "DE-NEW", bic = "X"))
        val (_, updates) = BankStatement.enrichExisting(existing, incoming)
        assertEquals(mapOf("bic" to "X"), updates[0].patch)
    }

    @Test fun guesses_vat_category() {
        assertEquals(listOf("19", "16", "7", "0", "private"), BankStatement.VAT_CATS)
        assertEquals("private", BankStatement.guessVatCat(ParsedTx(date = "", category = "Privateinlage")))
        assertEquals("private", BankStatement.guessVatCat(ParsedTx(date = "", purpose = "Privatentnahme")))
        assertEquals("19", BankStatement.guessVatCat(ParsedTx(date = "", category = "Umsatzsteuer 19%")))
        assertEquals("7", BankStatement.guessVatCat(ParsedTx(date = "", category = "USt 7 %")))
        assertEquals("0", BankStatement.guessVatCat(ParsedTx(date = "", purpose = "steuerfrei")))
        assertEquals("", BankStatement.guessVatCat(ParsedTx(date = "", purpose = "Bürobedarf")))
    }

    @Test fun classifies_payment_type() {
        assertEquals("card", BankStatement.classifyTxType(ParsedTx(date = "", bookingText = "SEPA-ELV-LASTSCHRIFT", amount = -30.0)))
        assertEquals("card", BankStatement.classifyTxType(ParsedTx(date = "", bookingText = "Kartenzahlung", amount = -10.0)))
        assertEquals("debit", BankStatement.classifyTxType(ParsedTx(date = "", bookingText = "FOLGELASTSCHRIFT", amount = -149.0)))
        assertEquals("debit", BankStatement.classifyTxType(ParsedTx(date = "", bookingText = "EINZUG RATE/ANNUITAET", amount = -213.0)))
        assertEquals("credit", BankStatement.classifyTxType(ParsedTx(date = "", bookingText = "GUTSCHR. UEBERW. DAUERAUFTR", amount = 2200.0)))
        assertEquals("standingorder", BankStatement.classifyTxType(ParsedTx(date = "", bookingText = "DAUERAUFTRAG", amount = -410.0)))
        assertEquals("transfer", BankStatement.classifyTxType(ParsedTx(date = "", bookingText = "Echtzeitüberweisung", amount = 150.0)))
        assertEquals("credit", BankStatement.classifyTxType(ParsedTx(date = "", bookingText = "", amount = 50.0)))
        assertEquals("other", BankStatement.classifyTxType(ParsedTx(date = "", bookingText = "", amount = -50.0)))
    }
}
