package de.ledgerline.app.core.finance

import de.ledgerline.app.domain.model.Receipt
import de.ledgerline.app.domain.model.Transaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Self-issued voucher (Eigenbeleg) for a bank booking with no original receipt — byte-shape parity
 * with the web (`resources/js/components/invoices.js`): a prefilled paper-form the user completes,
 * rendered to a PDF on-device and attached to the booking as a receipt with `kind:'eigenbeleg'` and
 * the field snapshot under `eigenbeleg`. Pure logic + JSON assembly (no Android deps → unit-testable).
 * No input-VAT deduction from a self-receipt (informational).
 */
object Eigenbeleg {
    /** Beleggrund options, mirroring the user's long-standing paper form (web `egGrundOptions`). */
    val GRUND_OPTIONS = listOf("privatentnahme", "privateinlage", "trinkgeld", "betriebsausgabe", "sachgeschenk", "sonstiges")

    /** The editable form state. */
    data class Draft(
        val grund: String = "betriebsausgabe",
        val grundOther: String = "",
        val recipient: String = "",
        val address: String = "",
        val ort: String = "",
        val date: String = "",
        val createdAt: String = "",
        val buchungstext: String = "",
        val gross: Double = 0.0,
        val vatRate: Double = 19.0,
        val reason: String = "",
        val issuer: String = "",
        /** PNG data-URI of the on-device signature (embedded into the sealed PDF), or "". */
        val signature: String = "",
    ) {
        val isExpense: Boolean get() = grund == "betriebsausgabe"
        val net: Double get() = round2(gross / (1 + vatRate / 100))
        val vat: Double get() = round2(gross - net)
        /** Business expense also requires a recipient + a reason for the missing original. */
        val valid: Boolean get() = gross > 0 && (!isExpense || (recipient.isNotBlank() && reason.isNotBlank()))
    }

    /** Prefill from a booking (web `newEigenbeleg`): guess the Beleggrund from sign + VAT category. */
    fun prefill(tx: Transaction, companyName: String, companyAddressLastLine: String, defaultVatRate: Double, today: String): Draft {
        val amt = tx.amount
        val grund = if (tx.vatCat == "private") (if (amt >= 0) "privateinlage" else "privatentnahme") else "betriebsausgabe"
        return Draft(
            grund = grund,
            recipient = tx.counterparty,
            ort = companyAddressLastLine,
            date = tx.date.ifBlank { today },
            createdAt = today,
            buchungstext = (tx.purpose.ifBlank { "" }).take(300),
            gross = abs(amt),
            vatRate = defaultVatRate,
            issuer = companyName,
        )
    }

    /** The `eigenbeleg` field snapshot JSON stored on the receipt (web `up.eigenbeleg = {...e, net, vat}`). */
    fun snapshot(d: Draft): JsonObject = buildJsonObject {
        put("grund", JsonPrimitive(d.grund))
        if (d.grundOther.isNotBlank()) put("grundOther", JsonPrimitive(d.grundOther))
        put("recipient", JsonPrimitive(d.recipient))
        if (d.address.isNotBlank()) put("address", JsonPrimitive(d.address))
        put("ort", JsonPrimitive(d.ort))
        put("date", JsonPrimitive(d.date))
        put("createdAt", JsonPrimitive(d.createdAt))
        put("buchungstext", JsonPrimitive(d.buchungstext))
        put("gross", JsonPrimitive(d.gross))
        put("vatRate", JsonPrimitive(d.vatRate))
        put("reason", JsonPrimitive(d.reason))
        put("issuer", JsonPrimitive(d.issuer))
        if (d.signature.isNotBlank()) put("signature", JsonPrimitive(d.signature))
        put("net", JsonPrimitive(d.net))
        put("vat", JsonPrimitive(d.vat))
    }

    /** Build the receipt record (`kind:'eigenbeleg'` + snapshot in raw) for the uploaded PDF blob. */
    fun receipt(id: String, name: String, blob: String, key: String, sig: String?, d: Draft): Receipt {
        val raw = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("name", JsonPrimitive(name))
            put("mime", JsonPrimitive("application/pdf"))
            put("blob", JsonPrimitive(blob))
            put("key", JsonPrimitive(key))
            if (sig != null) put("sig", JsonPrimitive(sig))
            put("kind", JsonPrimitive("eigenbeleg"))
            put("eigenbeleg", snapshot(d))
        }
        return Receipt(id = id, name = name, mime = "application/pdf", sig = sig, blob = blob, key = key, kind = "eigenbeleg", raw = raw)
    }

    private fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0
}
