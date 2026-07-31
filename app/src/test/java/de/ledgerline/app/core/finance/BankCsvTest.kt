package de.ledgerline.app.core.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankCsvTest {
    @Test fun parseAmount_german_and_plain() {
        assertEquals(1992.43, BankCsv.parseAmount("1.992,43")!!, 1e-9)
        assertEquals(-175.28, BankCsv.parseAmount("-175,28")!!, 1e-9)
        assertEquals(150.0, BankCsv.parseAmount("150.00")!!, 1e-9)
        assertEquals(213.51, BankCsv.parseAmount("213,51")!!, 1e-9)
        assertEquals(-42.0, BankCsv.parseAmount("(42,00)")!!, 1e-9)
        assertEquals(-9.5, BankCsv.parseAmount("9,50-")!!, 1e-9)
        assertNull(BankCsv.parseAmount("abc"))
    }

    @Test fun parseDate_formats() {
        assertEquals("2026-07-31", BankCsv.parseDate("31.07.2026"))
        assertEquals("2026-07-31", BankCsv.parseDate("2026-07-31"))
        assertEquals("2026-07-05", BankCsv.parseDate("5/7/26"))
        assertNull(BankCsv.parseDate(""))
    }

    @Test fun splitLine_honours_quotes() {
        val r = BankCsv.splitLine(""""a;b";c;"d""e"""", ';')
        assertEquals(listOf("a;b", "c", "d\"e"), r)
    }

    @Test fun parse_sparkasse_semicolon() {
        val csv = """
            Buchungstag;Betrag;Verwendungszweck;Beguenstigter/Zahlungspflichtiger;IBAN
            31.07.2026;-175,28;Miete Juli;Vermieter GmbH;DE12345678901234567890
            30.07.2026;1.992,43;Gehalt;Arbeitgeber AG;DE99887766554433221100
        """.trimIndent()
        val lines = BankCsv.parse(csv)
        assertEquals(2, lines.size)
        assertEquals("2026-07-31", lines[0].date)
        assertEquals(-175.28, lines[0].amount, 1e-9)
        assertEquals("Miete Juli", lines[0].purpose)
        assertEquals("Vermieter GmbH", lines[0].counterparty)
        assertEquals(1992.43, lines[1].amount, 1e-9)

        // toJson maps to the server field names.
        val j = BankCsv.toJson(lines[0])
        assertEquals("2026-07-31", (j["date"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue((j["counterparty_iban"] as kotlinx.serialization.json.JsonPrimitive).content.startsWith("DE12"))
    }

    @Test fun parse_rejects_non_statement() {
        assertTrue(BankCsv.parse("name;value\nfoo;bar").isEmpty())
    }
}
