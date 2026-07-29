package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.Partner

/**
 * Receipt naming + merchant→category learning — ports of the web `shared/receipt-name.js` and
 * `shared/merchant-learn.js`. Pure. The partner list doubles as the learned-category rule store
 * (no new collection), so the cross-client store shape is unchanged.
 */
object ReceiptEnrich {

    private fun compactDate(d: String?): String =
        Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(d ?: "")?.let { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] } ?: ""

    private fun clean(s: String?): String =
        (s ?: "").replace(Regex("[;/\\\\:*?\"<>|]+"), " ").replace(Regex("\\s{2,}"), " ").trim()

    /** `"YYYYMMDD; Partner; Beleg"` (or `"…; Rechnung <number>"`) + the original extension. */
    fun buildReceiptName(
        date: String?, partner: String?, number: String?, ext: String = "",
        belegWord: String = "Beleg", invoiceWord: String = "Rechnung",
    ): String {
        val segs = mutableListOf<String>()
        compactDate(date).takeIf { it.isNotEmpty() }?.let { segs.add(it) }
        clean(partner).takeIf { it.isNotEmpty() }?.let { segs.add(it.take(60)) }
        val num = clean(number)
        segs.add(if (num.isNotEmpty()) "${clean(invoiceWord).ifEmpty { "Rechnung" }} $num" else clean(belegWord).ifEmpty { "Beleg" })
        var e = ext.trim()
        if (e.isNotEmpty() && !e.startsWith(".")) e = ".$e"
        return segs.joinToString("; ") + e
    }

    /** Normalise a merchant name for matching (drop legal forms + punctuation). */
    fun normMerchant(s: String?): String =
        (s ?: "").lowercase()
            .replace(Regex("\\b(gmbh|mbh|ug|ag|kg|ohg|gbr|e\\.?k\\.?|co\\.?|deutschland|ltd|limited|llc|inc|international|distribution)\\b"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /** The partner (rule holder) matching a merchant [name], or null. */
    fun matchPartner(partners: List<Partner>, name: String?): Partner? {
        val nk = normMerchant(name)
        if (nk.length < 2) return null
        return partners.firstOrNull { normMerchant(it.name) == nk }
    }

    /** The category the user has taught for this merchant, or "". */
    fun learnedCategoryFor(partners: List<Partner>, name: String?): String =
        matchPartner(partners, name)?.category ?: ""
}
