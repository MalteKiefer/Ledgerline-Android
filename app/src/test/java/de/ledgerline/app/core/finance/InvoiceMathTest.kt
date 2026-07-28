package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.InvoiceLine
import de.ledgerline.app.domain.model.InvoiceStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** Byte-near checks for invoice totals + GoBD numbering vs the web `invoices.js` / `invoice-numbering.js`. */
class InvoiceMathTest {

    private fun inv(id: String, year: String, seq: Int? = null, status: InvoiceStatus = InvoiceStatus.DRAFT, lines: List<InvoiceLine> = emptyList(), number: String? = null, trashed: Boolean = false) =
        Invoice(id = id, number = number, seq = seq, status = status, issueDate = "$year-06-15", lines = lines, trashed = trashed)

    @Test fun totals_net_vat_gross_by_rate() {
        val i = inv("1", "2026", lines = listOf(
            InvoiceLine(desc = "A", qty = 2.0, unitPrice = 100.0, vatRate = 19.0), // net 200, vat 38
            InvoiceLine(desc = "B", qty = 1.0, unitPrice = 50.0, vatRate = 7.0),   // net 50, vat 3.5
            InvoiceLine(desc = "C", qty = 3.0, unitPrice = 10.0, vatRate = 19.0),  // net 30, vat 5.7
        ))
        val t = InvoiceMath.totals(i)
        assertEquals(280.0, t.net, 1e-9)
        assertEquals(43.7 + 3.5, t.vat, 1e-9) // 38+5.7 (19%) + 3.5 (7%)
        assertEquals(43.7, t.vatByRate[19.0]!!, 1e-9)
        assertEquals(3.5, t.vatByRate[7.0]!!, 1e-9)
        assertEquals(280.0 + 47.2, t.gross, 1e-9)
    }

    @Test fun numbering_per_year_restarts_and_honours_floor() {
        val invoices = listOf(
            inv("a", "2025", seq = 7),
            inv("b", "2026", seq = 3),
            inv("c", "2026", seq = 5),
        )
        assertEquals(6, InvoiceMath.nextSeqForYear(invoices, "2026", floor = 1)) // max(5)+1
        assertEquals(8, InvoiceMath.nextSeqForYear(invoices, "2025", floor = 1)) // max(7)+1
        assertEquals(1, InvoiceMath.nextSeqForYear(invoices, "2027", floor = 1)) // no invoices → floor
        assertEquals(100, InvoiceMath.nextSeqForYear(invoices, "2027", floor = 100)) // floor wins
    }

    @Test fun format_number_template() {
        assertEquals("2026-0042", InvoiceMath.formatNumber("YYYY-NNNN", 42, "2026-06-15"))
        assertEquals("26/06/7", InvoiceMath.formatNumber("YY/MM/N", 7, "2026-06-15"))
        assertEquals("2026-0001", InvoiceMath.formatNumber(null, 1, "2026-01-03")) // default
    }

    @Test fun duplicate_numbers_flagged() {
        val invoices = listOf(
            inv("a", "2026", number = "2026-0001"),
            inv("b", "2026", number = "2026-0001"),
            inv("c", "2026", number = "2026-0002"),
        )
        assertEquals(listOf("2026-0001"), InvoiceMath.duplicateNumbers(invoices))
    }

    @Test fun year_kpis() {
        val invoices = listOf(
            inv("a", "2026", status = InvoiceStatus.PAID, lines = listOf(InvoiceLine(qty = 1.0, unitPrice = 100.0, vatRate = 0.0))),
            inv("b", "2026", status = InvoiceStatus.SENT, lines = listOf(InvoiceLine(qty = 1.0, unitPrice = 50.0, vatRate = 0.0))),
            inv("c", "2025", status = InvoiceStatus.PAID, lines = listOf(InvoiceLine(qty = 1.0, unitPrice = 999.0, vatRate = 0.0))),
        )
        val k = InvoiceMath.yearKpis(invoices, 2026)
        assertEquals(100.0, k.paidYear, 1e-9)
        assertEquals(50.0, k.outstandingYear, 1e-9)
        assertEquals(2, k.countYear)
    }
}
