package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.InvoiceStatus
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Match an incoming bank transaction to an issued invoice (client-side, ZK) — a port of the web
 * `shared/invoice-match.js`. Used to auto-mark invoices paid and attach the invoice to the
 * transaction. Pure + testable.
 */
object InvoiceMatch {

    private fun norm(s: String?): String = (s ?: "").lowercase().replace(Regex("\\s+"), " ").trim()

    /** The `paymentTxId` link stored on the raw invoice (empty = not yet linked to a payment). */
    private fun paymentTxId(inv: Invoice): String =
        inv.raw["paymentTxId"]?.jsonPrimitive?.contentOrNull ?: ""

    /**
     * Candidate issued invoices: not trashed, numbered, not a draft, and not already linked to a
     * payment. Paid invoices stay eligible — imported invoices are created "paid".
     */
    private fun candidates(invoices: List<Invoice>): List<Invoice> =
        invoices.filter { !it.trashed && !it.number.isNullOrEmpty() && it.status != InvoiceStatus.DRAFT && paymentTxId(it).isEmpty() }

    /**
     * The issued invoice an income transaction most likely settles, or null. Only positive amounts
     * match; amounts must match to the cent. Strongest signal first: (1) invoice number in the
     * purpose/reference + amount, (2) customer name in the text + amount, (3) a unique exact amount.
     */
    fun matchInvoice(tx: BankStatement.ParsedTx, invoices: List<Invoice>): Invoice? {
        if (tx.amount <= 0) return null
        val gross = Math.round(tx.amount * 100.0) / 100.0
        val hay = norm("${tx.purpose} ${tx.eref} ${tx.counterparty}")
        val cands = candidates(invoices)
        fun amountEq(inv: Invoice) = Math.abs(InvoiceMath.totals(inv).gross - gross) < 0.005

        cands.firstOrNull { inv -> hay.contains(norm(inv.number)) && amountEq(inv) }?.let { return it }
        cands.firstOrNull { inv ->
            val name = norm(inv.customer.name)
            name.length >= 3 && hay.contains(name) && amountEq(inv)
        }?.let { return it }
        val byAmount = cands.filter { amountEq(it) }
        return if (byAmount.size == 1) byAmount[0] else null
    }
}
