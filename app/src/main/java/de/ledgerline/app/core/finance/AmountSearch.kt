package de.ledgerline.app.core.finance

import java.util.Locale

/**
 * Free-text amount matching — a port of the web `shared/amount-search.js`. Accepts `.` or `,` as the
 * decimal separator and an optional leading `-`, ignoring spaces/currency; substring-matches against
 * both the signed and absolute two-decimal rendering ("-9" matches -9.88, "9,88" matches 9.88).
 */
object AmountSearch {

    fun amountMatches(amount: Double, query: String?): Boolean {
        var q = (query ?: "").lowercase().replace("eur", "").replace(Regex("[\\s€]"), "").replace(",", ".")
        q = q.replace(Regex("[^0-9.-]"), "")
        if (!Regex("[0-9]").containsMatchIn(q)) return false
        if (amount.isNaN() || amount.isInfinite()) return false
        val signed = String.format(Locale.ROOT, "%.2f", amount)
        val abs = String.format(Locale.ROOT, "%.2f", kotlin.math.abs(amount))
        return signed.contains(q) || abs.contains(q)
    }
}
