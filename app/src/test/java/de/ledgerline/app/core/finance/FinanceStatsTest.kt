package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.InvoiceCustomer
import de.ledgerline.app.domain.model.InvoiceLine
import de.ledgerline.app.domain.model.InvoiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceStatsTest {

    private fun inv(
        id: String,
        date: String,
        status: InvoiceStatus,
        customer: String,
        lines: List<InvoiceLine>,
        trashed: Boolean = false,
    ) = Invoice(
        id = id, status = status, issueDate = date, trashed = trashed,
        customer = InvoiceCustomer(name = customer), lines = lines,
    )

    private fun line(net: Double, vat: Double) = InvoiceLine(desc = "x", qty = 1.0, unitPrice = net, vatRate = vat)

    private val sample = listOf(
        inv("a", "2026-02-10", InvoiceStatus.PAID, "ACME", listOf(line(1000.0, 19.0))),
        inv("b", "2026-05-20", InvoiceStatus.SENT, "ACME", listOf(line(500.0, 7.0))),
        inv("c", "2026-08-01", InvoiceStatus.PAID, "Globex", listOf(line(200.0, 19.0), line(100.0, 0.0))),
        inv("d", "2026-03-03", InvoiceStatus.DRAFT, "Ignored", listOf(line(9999.0, 19.0))),      // draft: excluded
        inv("e", "2026-04-04", InvoiceStatus.PAID, "Trashed", listOf(line(9999.0, 19.0)), trashed = true), // excluded
        inv("f", "2025-06-06", InvoiceStatus.PAID, "OldCo", listOf(line(800.0, 19.0))),          // prior year
    )

    @Test fun realized_excludes_draft_and_trashed() {
        val ids = FinanceStats.realizedInvoices(sample).map { it.id }.toSet()
        assertEquals(setOf("a", "b", "c", "f"), ids)
    }

    @Test fun vat_return_nets_and_rates() {
        val r = FinanceStats.vatReturn(sample, 2026)
        assertEquals(3, r.count)                 // a, b, c
        assertEquals(1800.0, r.net, 0.001)       // 1000 + 500 + 300
        assertEquals(263.0, r.vat, 0.001)        // 190 + 35 + 38 + 0
        assertEquals(2063.0, r.gross, 0.001)
        // Rates sorted ascending: 0, 7, 19
        assertEquals(listOf(0.0, 7.0, 19.0), r.byRate.map { it.rate })
        val r19 = r.byRate.first { it.rate == 19.0 }
        assertEquals(1200.0, r19.net, 0.001)     // 1000 + 200
        assertEquals(228.0, r19.vat, 0.001)      // 190 + 38
        val r7 = r.byRate.first { it.rate == 7.0 }
        assertEquals(500.0, r7.net, 0.001)
        assertEquals(35.0, r7.vat, 0.001)
    }

    @Test fun vat_return_by_quarter() {
        val q = FinanceStats.vatReturn(sample, 2026).quarters
        assertEquals(4, q.size)
        assertEquals(1000.0, q[0].net, 0.001)    // Q1: Feb (a)
        assertEquals(500.0, q[1].net, 0.001)     // Q2: May (b)
        assertEquals(300.0, q[2].net, 0.001)     // Q3: Aug (c)
        assertEquals(0.0, q[3].net, 0.001)       // Q4: none
    }

    @Test fun revenue_by_customer_sorted() {
        val rows = FinanceStats.revenueByCustomer(sample, 2026)
        assertEquals(listOf("ACME", "Globex"), rows.map { it.name })
        assertEquals(1500.0, rows[0].net, 0.001) // 1000 + 500
        assertEquals(2, rows[0].count)
        assertEquals(300.0, rows[1].net, 0.001)
    }

    @Test fun monthly_revenue_indexes_by_month() {
        val m = FinanceStats.monthlyRevenue(sample, 2026)
        assertEquals(12, m.size)
        assertEquals(1000.0, m[1], 0.001)  // February
        assertEquals(500.0, m[4], 0.001)   // May
        assertEquals(300.0, m[7], 0.001)   // August
        assertEquals(0.0, m[0], 0.001)     // January
    }

    @Test fun kpis_growth_vs_previous_year() {
        val k = FinanceStats.statsKpis(sample, 2026)
        assertEquals(1800.0, k.net, 0.001)
        assertEquals(3, k.count)
        assertEquals(2, k.customers)
        assertEquals(800.0, k.prevNet, 0.001)
        assertEquals(125.0, k.growthPct!!, 0.001)   // (1800-800)/800 = 125%
    }

    @Test fun kpis_null_growth_when_no_prior_revenue() {
        val only = listOf(sample.first())
        assertNull(FinanceStats.statsKpis(only, 2026).growthPct)
    }

    @Test fun empty_year_is_zero() {
        val r = FinanceStats.vatReturn(sample, 2099)
        assertEquals(0, r.count)
        assertEquals(0.0, r.net, 0.001)
        assertTrue(FinanceStats.revenueByCustomer(sample, 2099).isEmpty())
    }
}
