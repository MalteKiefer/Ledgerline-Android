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
    val bic: String = "",
    val bankName: String = "",
    val currency: String = "EUR",
    /** Default VAT rate (%) pre-filled on new invoice lines (web `invoice_default_vat_rate`). */
    val defaultVatRate: Double = 19.0,
    val paymentTermsDays: Int = 14,
    /** Free-text payment terms + accepted payment methods printed on the invoice. */
    val paymentTermsText: String = "",
    val paymentMethods: String = "",
    val footerText: String = "",
    /** Invoice-number template (web `number_format`, default `YYYY-NNNN`) + the GoBD floor. */
    val numberFormat: String = "YYYY-NNNN",
    val nextNumber: Int = 1,
    /** Whether a company logo is stored server-side (streamed from `GET /company/logo`). */
    val hasLogo: Boolean = false,
    val raw: JsonObject = JsonObject(emptyMap()),
)

/**
 * A payment method (bank account, card, PayPal, cash, …) in the finance store's `paymentMethods`
 * collection (byte-shape = web `blankPaymentMethod`). Sensitive fields are ZK — sealed client-side.
 */
data class PaymentMethod(
    val id: String,
    val type: String = "bank",          // bank | card | paypal | cash | other
    val label: String = "",
    val holder: String = "",
    val iban: String = "",
    val bic: String = "",
    val bankName: String = "",
    val accountNumber: String = "",
    val url: String = "",
    val cardNetwork: String = "visa",   // visa | mastercard | amex | other
    val cardNumber: String = "",
    val cardExpiry: String = "",
    val email: String = "",
    val note: String = "",
    /** The single "business" account (only one at a time); non-business = private-scope. */
    val business: Boolean = false,
    val trashed: Boolean = false,
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** A bank/account booking in the `transactions` collection (read-only in Android for now). */
data class Transaction(
    val id: String,
    val account: String = "",           // the PaymentMethod.id this booking belongs to
    val date: String = "",              // YYYY-MM-DD
    val amount: Double = 0.0,           // signed: >0 income, <0 expense
    val currency: String = "EUR",
    val counterparty: String = "",
    val purpose: String = "",
    val vatCat: String = "",            // '', a rate ('19'), or 'private'
    val invoiceId: String? = null,
    val trashed: Boolean = false,
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** A manual "hand" expense bundled in a cost project (web `project.expenses[]`). */
data class ProjectExpense(
    val id: String,
    val amount: Double = 0.0,
    val date: String = "",
    val note: String = "",
    val account: String? = null,    // optional payment-method id this expense was paid from
    val category: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
)

/**
 * A nestable cost project bundling receipts + manual expenses (finance store `projects` collection).
 * `kind` separates business vs private projects (web `92ba1eb4`); totals roll up through `parentId`.
 */
data class Project(
    val id: String,
    val name: String = "",
    val parentId: String? = null,
    val note: String = "",
    val kind: String = "business",       // business | private
    val expenses: List<ProjectExpense> = emptyList(),
    val created: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** A contact person (Ansprechpartner) for a business partner (`partner.contacts[]`). */
data class PartnerContact(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val role: String = "",           // function/position
)

/** A business partner (merchant/client) in the finance store's `partners` collection (partRef). */
data class Partner(
    val id: String,
    val name: String = "",
    val category: String = "",       // the learned booking category for this merchant
    val note: String = "",
    val url: String = "",            // website; the logo is fetched from its host
    val logo: String = "",           // data: URI favicon/logo (sealed)
    val address: String = "",
    val email: String = "",
    val phone: String = "",
    val vatId: String = "",          // USt-IdNr.
    /** Contact persons; the legacy single `contact` string is migrated into `contacts[0]`. */
    val contacts: List<PartnerContact> = emptyList(),
    val raw: JsonObject = JsonObject(emptyMap()),
)

/**
 * A receipt/document bundled on a transaction (inline `tx.receipts[]`). The document itself is a ZK
 * content blob (`blob` id + `key` = sealed encFileKey, uploaded via `/invoices/upload`, same format
 * as file blobs); `total`/`projectId`/`category` are the bookkeeping metadata. Byte-shape = web.
 */
data class Receipt(
    val id: String,
    val name: String = "",
    val mime: String = "application/octet-stream",
    val total: Double? = null,
    val projectId: String? = null,
    val partnerId: String? = null,
    val category: String = "",
    val sig: String? = null,
    val blob: String? = null,
    /** Sealed per-blob content key (`{c,n}` JSON string), used to decrypt the document. */
    val key: String? = null,
    /** Receipt kind: "" = an ordinary attached document, "eigenbeleg" = a self-issued voucher. */
    val kind: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val isEigenbeleg: Boolean get() = kind == "eigenbeleg"
}

/** The decrypted `/invoices/store` slice the app consumes. `seq` = the GoBD per-year counter base. */
data class FinanceManifest(
    val invoices: List<Invoice> = emptyList(),
    val paymentMethods: List<PaymentMethod> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val projects: List<Project> = emptyList(),
    val partners: List<Partner> = emptyList(),
    val seq: Int = 0,
)

/** Manifest + optimistic-concurrency version. */
data class FinanceStore(val manifest: FinanceManifest, val version: Int)
