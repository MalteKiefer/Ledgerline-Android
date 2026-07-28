package de.ledgerline.app.domain.model

import kotlinx.serialization.json.JsonObject

/**
 * Finance / invoicing data model (ZK — invoices live in the sealed sharded `/invoices/store`;
 * the company profile is non-secret business identity served by `GET/PUT /company`). Known fields
 * are typed; the original decoded JSON is kept in [raw] so foreign/future keys (e.g. `footer`,
 * `pdfRef`, ZUGFeRD attachments, partner links) survive an Android read-modify-write — the same
 * no-data-loss overlay as the other modules. Money is kept as canonical minor-unit-free doubles
 * exactly as the web stores them (`unitPrice`, `qty`); totals are derived, never stored.
 */

/** One invoice line. `net = qty * unitPrice`; `vatRate` is a whole-number percent (19, 7, 0…). */
data class InvoiceLine(
    val desc: String = "",
    val qty: Double = 1.0,
    val unit: String = "",
    val unitPrice: Double = 0.0,
    val vatRate: Double = 0.0,
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** The invoice recipient (byte-shape = web `inv.customer`). */
data class InvoiceCustomer(
    val name: String = "",
    val attn: String = "",
    val address: String = "",
    val email: String = "",
    val vatId: String = "",
    val contactId: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** Invoice status. `draft` has no number yet; `sent` is issued/outstanding; `paid` is settled. */
enum class InvoiceStatus { DRAFT, SENT, PAID, UNKNOWN;
    companion object {
        fun from(s: String?): InvoiceStatus = when (s) {
            "draft" -> DRAFT; "sent" -> SENT; "paid" -> PAID; else -> UNKNOWN
        }
    }
    val wire: String get() = when (this) { DRAFT -> "draft"; SENT -> "sent"; PAID -> "paid"; UNKNOWN -> "draft" }
}

/** A single invoice (byte-shape = web `newInvoice`). `number` is null until the invoice is issued. */
data class Invoice(
    val id: String,
    val number: String? = null,
    /** GoBD per-year sequence stored on the invoice (numbering derives from real data). */
    val seq: Int? = null,
    val status: InvoiceStatus = InvoiceStatus.DRAFT,
    val issueDate: String = "",      // YYYY-MM-DD
    val dueDate: String = "",        // YYYY-MM-DD
    val currency: String = "EUR",
    val lang: String = "de",
    val customer: InvoiceCustomer = InvoiceCustomer(),
    val lines: List<InvoiceLine> = emptyList(),
    val note: String = "",
    val footer: String = "",
    val trashed: Boolean = false,
    val updated: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** Derived invoice totals — never stored (recomputed on demand, web `computeTotals`). */
data class InvoiceTotals(
    val net: Double = 0.0,
    val vatByRate: Map<Double, Double> = emptyMap(),
    val vat: Double = 0.0,
    val gross: Double = 0.0,
)

/** Non-secret business identity for invoices (from `GET /company`). Additive; unknown keys kept. */
data class CompanyProfile(
    val name: String = "",
    val address: String = "",
    val email: String = "",
    val phone: String = "",
    val vatId: String = "",
    val taxNumber: String = "",
    val iban: String = "",
    val currency: String = "EUR",
    val paymentTermsDays: Int = 14,
    val footerText: String = "",
    /** Invoice-number template (web `number_format`, default `YYYY-NNNN`) + the GoBD floor. */
    val numberFormat: String = "YYYY-NNNN",
    val nextNumber: Int = 1,
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** The decrypted `/invoices/store` slice the app consumes. `seq` = the GoBD per-year counter base. */
data class FinanceManifest(
    val invoices: List<Invoice> = emptyList(),
    val seq: Int = 0,
)

/** Manifest + optimistic-concurrency version. */
data class FinanceStore(val manifest: FinanceManifest, val version: Int)
