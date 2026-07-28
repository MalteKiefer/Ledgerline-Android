package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.InvoiceLine
import de.ledgerline.app.domain.model.InvoiceStatus
import de.ledgerline.app.domain.model.InvoiceTotals
import kotlin.math.max

/**
 * Pure invoice arithmetic + GoBD numbering — faithful ports of the web `invoices.js` `computeTotals`
 * and `shared/invoice-numbering.js`. No side effects, unit-testable. Money stays as doubles exactly
 * as the web computes them (no minor-unit rounding at rest); display formatting rounds for output.
 */
object InvoiceMath {

    /** Line net = qty × unitPrice (web `lineNet`). */
    fun lineNet(l: InvoiceLine): Double = l.qty * l.unitPrice

    /** Net + per-rate VAT + total VAT + gross (web `computeTotals`). */
    fun totals(inv: Invoice): InvoiceTotals {
        var net = 0.0
        var vat = 0.0
        val byRate = LinkedHashMap<Double, Double>()
        for (l in inv.lines) {
            val n = lineNet(l)
            net += n
            val v = n * l.vatRate / 100.0
            byRate[l.vatRate] = (byRate[l.vatRate] ?: 0.0) + v
            vat += v
        }
        return InvoiceTotals(net = net, vatByRate = byRate, vat = vat, gross = net + vat)
    }

    // ---- GoBD numbering (byte-near invoice-numbering.js) ----

    /** The YYYY year an invoice is dated in. */
    fun invoiceYear(inv: Invoice): String = inv.issueDate.take(4)

    /** Highest issued sequence among invoices dated in [year]. */
    fun maxSeqForYear(invoices: List<Invoice>, year: String): Int =
        invoices.filter { invoiceYear(it) == year }.mapNotNull { it.seq }.maxOrNull() ?: 0

    /** Next sequence for [year]: one past that year's max, never below the company [floor]. */
    fun nextSeqForYear(invoices: List<Invoice>, year: String, floor: Int): Int =
        max(maxSeqForYear(invoices, year) + 1, if (floor > 0) floor else 1)

    /** Active (non-trashed) invoices dated in [year]. */
    fun invoicesInYear(invoices: List<Invoice>, year: String): List<Invoice> =
        invoices.filter { !it.trashed && invoiceYear(it) == year }

    /** Invoice numbers assigned to more than one invoice (a GoBD violation to surface). */
    fun duplicateNumbers(invoices: List<Invoice>): List<String> =
        invoices.mapNotNull { it.number }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()

    /**
     * Format a number from a template + [seq] + [issueDate] (web `_formatNumber`): `YYYY`/`YY`/`MM`/
     * `DD` become the date parts; a run of `N` becomes the zero-padded sequence. Default `YYYY-NNNN`.
     */
    fun formatNumber(template: String?, seq: Int, issueDate: String): String {
        val fmt = template?.ifBlank { null } ?: "YYYY-NNNN"
        val (y, mm, dd) = parseYmd(issueDate)
        var out = fmt
            .replace("YYYY", y.toString())
            .replace("YY", y.toString().takeLast(2))
            .replace("MM", mm.toString().padStart(2, '0'))
            .replace("DD", dd.toString().padStart(2, '0'))
        // Replace each run of N's with the seq padded to that run's length (longest handled by regex).
        out = Regex("N+").replace(out) { m -> seq.toString().padStart(m.value.length, '0') }
        return out
    }

    private fun parseYmd(iso: String): Triple<Int, Int, Int> {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(iso)
        return if (m != null) Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        else Triple(java.time.LocalDate.now().year, java.time.LocalDate.now().monthValue, java.time.LocalDate.now().dayOfMonth)
    }

    /** Year KPIs for the list header: paid/outstanding gross this year + count (web `yearKpis`-ish). */
    data class YearKpis(val year: Int, val paidYear: Double, val outstandingYear: Double, val countYear: Int)

    fun yearKpis(invoices: List<Invoice>, year: Int): YearKpis {
        var paid = 0.0
        var outstanding = 0.0
        var count = 0
        for (inv in invoices) {
            if (inv.trashed) continue
            val g = totals(inv).gross
            val y = invoiceYear(inv).toIntOrNull() ?: continue
            if (y == year) {
                count++
                when (inv.status) {
                    InvoiceStatus.PAID -> paid += g
                    InvoiceStatus.SENT -> outstanding += g
                    else -> {}
                }
            }
        }
        return YearKpis(year, paid, outstanding, count)
    }
}
