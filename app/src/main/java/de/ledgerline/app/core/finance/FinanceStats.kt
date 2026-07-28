package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.InvoiceStatus
import kotlin.math.roundToLong

/**
 * Pure finance analytics over already-decrypted invoice records — a faithful port of the web
 * `shared/finance-stats.js`. Client-side and zero-knowledge (the server never sees invoice
 * contents). Feeds the VAT advance return (Umsatzsteuer-Voranmeldung) and the statistics view
 * (revenue by customer, monthly revenue, year-over-year growth). Totals reuse [InvoiceMath.totals].
 */
object FinanceStats {

    /** Round to 2 decimals like the web `round2` (half-up on the cent). */
    private fun round2(n: Double): Double = (n * 100.0).roundToLong() / 100.0

    private fun yearOf(inv: Invoice): Int = inv.issueDate.take(4).toIntOrNull() ?: 0
    private fun monthOf(inv: Invoice): Int = inv.issueDate.drop(5).take(2).toIntOrNull() ?: 0

    /** Invoices that count as revenue: issued (sent or paid) and not trashed (web `realizedInvoices`). */
    fun realizedInvoices(invoices: List<Invoice>): List<Invoice> =
        invoices.filter { !it.trashed && (it.status == InvoiceStatus.PAID || it.status == InvoiceStatus.SENT) }

    data class RateRow(val rate: Double, val net: Double, val vat: Double)
    data class Quarter(val q: Int, val net: Double, val vat: Double)
    data class VatReturn(
        val year: Int,
        val net: Double,
        val vat: Double,
        val gross: Double,
        val count: Int,
        val byRate: List<RateRow>,
        val quarters: List<Quarter>,
    )

    /**
     * VAT advance return figures for a [year]: net turnover and VAT owed, broken down by rate and by
     * quarter (web `vatReturn`). Only realized (sent/paid) invoices count.
     */
    fun vatReturn(invoices: List<Invoice>, year: Int): VatReturn {
        val list = realizedInvoices(invoices).filter { yearOf(it) == year }
        val quarters = DoubleArray(4)   // net per quarter
        val qVat = DoubleArray(4)
        val byRateNet = LinkedHashMap<Double, Double>()
        val byRateVat = LinkedHashMap<Double, Double>()
        var net = 0.0
        var vat = 0.0
        for (inv in list) {
            val t = InvoiceMath.totals(inv)
            net += t.net
            vat += t.vat
            for ((rate, v) in t.vatByRate) {
                byRateVat[rate] = (byRateVat[rate] ?: 0.0) + v
                byRateNet[rate] = (byRateNet[rate] ?: 0.0) + if (rate > 0) v / (rate / 100.0) else t.net
            }
            val q = ((monthOf(inv) + 2) / 3)   // 1..4
            if (q in 1..4) { quarters[q - 1] += t.net; qVat[q - 1] += t.vat }
        }
        val rows = byRateVat.keys.sorted().map { r ->
            RateRow(rate = r, net = round2(byRateNet[r] ?: 0.0), vat = round2(byRateVat[r] ?: 0.0))
        }
        return VatReturn(
            year = year,
            net = round2(net),
            vat = round2(vat),
            gross = round2(net + vat),
            count = list.size,
            byRate = rows,
            quarters = (0 until 4).map { Quarter(it + 1, round2(quarters[it]), round2(qVat[it])) },
        )
    }

    data class CustomerRevenue(val name: String, val net: Double, val gross: Double, val count: Int)

    /** Net + gross revenue per customer for a [year], highest net first (web `revenueByCustomer`). */
    fun revenueByCustomer(invoices: List<Invoice>, year: Int): List<CustomerRevenue> {
        val net = LinkedHashMap<String, Double>()
        val gross = LinkedHashMap<String, Double>()
        val count = LinkedHashMap<String, Int>()
        for (inv in realizedInvoices(invoices).filter { yearOf(it) == year }) {
            val name = inv.customer.name.ifBlank { "—" }
            val t = InvoiceMath.totals(inv)
            net[name] = (net[name] ?: 0.0) + t.net
            gross[name] = (gross[name] ?: 0.0) + t.gross
            count[name] = (count[name] ?: 0) + 1
        }
        return net.keys
            .map { CustomerRevenue(it, round2(net[it] ?: 0.0), round2(gross[it] ?: 0.0), count[it] ?: 0) }
            .sortedByDescending { it.net }
    }

    /** Net revenue per calendar month (index 0 = January) for a [year] (web `monthlyRevenue`). */
    fun monthlyRevenue(invoices: List<Invoice>, year: Int): List<Double> {
        val months = DoubleArray(12)
        for (inv in realizedInvoices(invoices).filter { yearOf(it) == year }) {
            val m = monthOf(inv)
            if (m in 1..12) months[m - 1] += InvoiceMath.totals(inv).net
        }
        return months.map { round2(it) }
    }

    data class StatsKpis(
        val year: Int,
        val net: Double,
        val count: Int,
        val avg: Double,
        val customers: Int,
        val prevNet: Double,
        /** Year-over-year net growth in percent, or null if the previous year had no revenue. */
        val growthPct: Double?,
    )

    /** Headline KPIs for a [year] incl. year-over-year net growth vs the previous year (web `yearKpis`). */
    fun statsKpis(invoices: List<Invoice>, year: Int): StatsKpis {
        val list = realizedInvoices(invoices).filter { yearOf(it) == year }
        val net = round2(list.sumOf { InvoiceMath.totals(it).net })
        val prevNet = round2(realizedInvoices(invoices).filter { yearOf(it) == year - 1 }.sumOf { InvoiceMath.totals(it).net })
        val customers = list.map { it.customer.name.ifBlank { "—" } }.toSet().size
        return StatsKpis(
            year = year,
            net = net,
            count = list.size,
            avg = if (list.isNotEmpty()) round2(net / list.size) else 0.0,
            customers = customers,
            prevNet = prevNet,
            growthPct = if (prevNet > 0) round2((net - prevNet) / prevNet * 100.0) else null,
        )
    }
}
