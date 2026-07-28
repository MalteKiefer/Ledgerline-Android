package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.PaymentMethod

/**
 * Pure payment-method helpers — a faithful port of the web `shared/payment-methods.js`. All masking,
 * subtitle, validity and sort logic lives here so the UI stays thin. Records are sealed client-side
 * (zero-knowledge) in the finance store's `paymentMethods` collection.
 */
object PaymentMethods {

    /** Supported types in display order (icon/tint chosen app-side; order = web `PAYMENT_TYPES`). */
    val TYPES = listOf("bank", "card", "paypal", "cash", "other")

    fun isPaymentType(type: String): Boolean = type in TYPES

    /** A blank record of a given [type] (falls back to `other`). */
    fun blank(id: String, type: String = "bank"): PaymentMethod =
        PaymentMethod(id = id, type = if (isPaymentType(type)) type else "other")

    /** Uppercase IBAN with everything but A–Z0–9 stripped. */
    fun normalizeIban(iban: String): String = iban.uppercase().replace(Regex("[^A-Z0-9]"), "")

    /** IBAN grouped into blocks of four for display. */
    fun formatIban(iban: String): String =
        normalizeIban(iban).replace(Regex("(.{4})"), "$1 ").trim()

    /** Last four significant characters of an IBAN/account number, or the whole thing if shorter. */
    fun last4(value: String): String {
        val s = value.replace(Regex("\\s"), "")
        return if (s.length >= 4) s.takeLast(4) else s
    }

    /** A masked IBAN keeping the country prefix and last four: `DE89 •••• •••• 3000`. */
    fun maskIban(iban: String): String {
        val n = normalizeIban(iban)
        if (n.isEmpty()) return ""
        if (n.length <= 8) return formatIban(n)
        val head = n.take(4)
        val tail = n.takeLast(4)
        val midGroups = maxOf(1, Math.ceil((n.length - 8) / 4.0).toInt())
        return (listOf(head) + List(midGroups) { "••••" } + listOf(tail)).joinToString(" ")
    }

    /** Digits only from a card number. */
    fun cardDigits(number: String): String = number.replace(Regex("\\D"), "")

    /** A masked card number keeping only the last four: `•••• •••• •••• 1234`. */
    fun maskCard(number: String): String {
        val d = cardDigits(number)
        if (d.isEmpty()) return ""
        return "•••• •••• •••• ${d.takeLast(4)}"
    }

    /** Guess the card network from the number's prefix. */
    fun cardNetworkOf(number: String): String {
        val d = cardDigits(number)
        return when {
            Regex("^4").containsMatchIn(d) -> "visa"
            Regex("^(5[1-5]|2[2-7])").containsMatchIn(d) -> "mastercard"
            Regex("^3[47]").containsMatchIn(d) -> "amex"
            else -> "other"
        }
    }

    /** The secondary line shown under a method's label in the list (web `paymentSubtitle`). */
    fun subtitle(pm: PaymentMethod): String = when (pm.type) {
        "bank" -> if (pm.iban.isNotEmpty()) maskIban(pm.iban) else pm.bankName.ifEmpty { pm.accountNumber }
        "card" -> {
            val net = pm.cardNetwork.replaceFirstChar { it.uppercase() }
            val masked = if (pm.cardNumber.isNotEmpty()) maskCard(pm.cardNumber) else ""
            listOf(net, masked).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        "paypal" -> pm.email
        "cash" -> ""
        else -> pm.note
    }

    /** True if a record has the minimum fields to be worth saving (web `isValidPaymentMethod`). */
    fun isValid(pm: PaymentMethod): Boolean {
        if (!isPaymentType(pm.type)) return false
        if (pm.label.trim().isEmpty()) return false
        return when (pm.type) {
            "bank" -> pm.iban.trim().isNotEmpty() || pm.accountNumber.trim().isNotEmpty()
            "card" -> pm.cardNumber.trim().isNotEmpty()
            "paypal" -> pm.email.trim().isNotEmpty()
            else -> true // cash / other only need a label
        }
    }

    /** Active (non-trashed) methods, banks first then cards, by label (web `sortedPaymentMethods`). */
    fun sorted(list: List<PaymentMethod>): List<PaymentMethod> {
        val order = mapOf("bank" to 0, "card" to 1, "paypal" to 2, "cash" to 3, "other" to 4)
        return list.filter { !it.trashed }
            .sortedWith(compareBy({ order[it.type] ?: 9 }, { it.label.lowercase() }))
    }
}
