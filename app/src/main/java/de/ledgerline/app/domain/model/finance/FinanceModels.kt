package de.ledgerline.app.domain.model.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Plaintext-relational finance models (server pivot v1.5xx — the zero-knowledge sealed-store model
 * was removed). Every record is an owner-scoped row with an integer [id], an optimistic-concurrency
 * [version], and soft-delete (`deleted_at`). Sensitive columns are encrypted at rest server-side and
 * arrive decrypted over TLS — there is NO client crypto. Field names mirror the API (snake_case) via
 * [SerialName]; decoding is lenient (`ignoreUnknownKeys`), so additive server fields never break us.
 *
 * Open-shaped columns (invoice `customer`/`lines`/`versions`, transaction `receipts`, partner
 * `contacts`, project `expenses`) are kept as raw [JsonElement] so we round-trip them losslessly and
 * type only what the UI needs on top.
 */

@Serializable
data class Invoice(
    val id: Int = 0,
    val number: String? = null,
    val seq: Int? = null,
    val year: Int? = null,
    val status: String = "draft", // draft | sent | paid
    val type: String = "invoice", // invoice | credit_note (Storno/Gutschrift)
    @SerialName("cancels_invoice_id") val cancelsInvoiceId: Int? = null,
    @SerialName("issue_date") val issueDate: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    val currency: String = "EUR",
    @SerialName("vat_rate") val vatRate: String? = null,
    val gross: String? = null,
    val net: String? = null,
    val vat: String? = null,
    @SerialName("discount_type") val discountType: String? = null, // percent | amount
    @SerialName("discount_value") val discountValue: String? = null,
    @SerialName("skonto_percent") val skontoPercent: String? = null,
    @SerialName("skonto_days") val skontoDays: Int? = null,
    val imported: Boolean = false,
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("reminded_at") val remindedAt: String? = null,
    @SerialName("reminder_count") val reminderCount: Int = 0,
    @SerialName("payment_account") val paymentAccount: String? = null,
    @SerialName("partner_id") val partnerId: Int? = null,
    @SerialName("invoice_email") val invoiceEmail: String? = null,
    @SerialName("pdf_path") val pdfPath: String? = null,
    val customer: JsonObject? = null,
    val lines: List<JsonObject> = emptyList(),
    val note: String? = null,
    val versions: List<JsonObject> = emptyList(),
    @SerialName("version_seq") val versionSeq: Int = 0,
    val version: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    val isCreditNote: Boolean get() = type == "credit_note"
}

@Serializable
data class FinancePartner(
    val id: Int = 0,
    val name: String = "",
    val category: String? = null,
    val kind: String? = null,
    val url: String? = null,
    val logo: String? = null,
    val note: String? = null,
    val address: String? = null,
    val email: String? = null,
    @SerialName("invoice_email") val invoiceEmail: String? = null,
    val phone: String? = null,
    @SerialName("vat_id") val vatId: String? = null,
    val contacts: List<JsonObject> = emptyList(),
    val version: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class PaymentMethod(
    val id: Int = 0,
    val type: String = "bank", // bank | card | paypal | cash | other
    val name: String = "",
    val holder: String? = null,
    val note: String? = null,
    val business: Boolean = false,
    val url: String? = null,
    val icon: String? = null,
    val iban: String? = null,
    val bic: String? = null,
    val bank: String? = null,
    @SerialName("account_no") val accountNo: String? = null,
    @SerialName("card_number") val cardNumber: String? = null,
    @SerialName("card_network") val cardNetwork: String? = null,
    @SerialName("card_expiry") val cardExpiry: String? = null,
    @SerialName("paypal_email") val paypalEmail: String? = null,
    val version: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class FinanceProject(
    val id: Int = 0,
    @SerialName("parent_id") val parentId: Int? = null,
    val name: String = "",
    val kind: String = "business", // business | private
    val note: String? = null,
    val expenses: List<JsonObject> = emptyList(),
    val version: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class FinanceCategory(
    val id: Int = 0,
    val name: String = "",
    val color: String? = null, // #RRGGBB
    val icon: String? = null,  // heroicons-outline name
)

@Serializable
data class BankTransaction(
    val id: Int = 0,
    @SerialName("payment_method_id") val paymentMethodId: Int = 0,
    val date: String = "",
    val amount: String = "0",
    @SerialName("vat_cat") val vatCat: String? = null,
    val sig: String? = null,
    @SerialName("invoice_id") val invoiceId: Int? = null,
    @SerialName("invoice_number") val invoiceNumber: String? = null,
    @SerialName("finance_project_id") val financeProjectId: Int? = null,
    val counterparty: String? = null,
    @SerialName("counterparty_iban") val counterpartyIban: String? = null,
    val bic: String? = null,
    val purpose: String? = null,
    @SerialName("booking_text") val bookingText: String? = null,
    val eref: String? = null,
    val receipts: List<JsonObject> = emptyList(),
    val version: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** The full owner-scoped finance snapshot (`GET /finance/data`). */
@Serializable
data class FinanceData(
    val invoices: List<Invoice> = emptyList(),
    val partners: List<FinancePartner> = emptyList(),
    val paymentMethods: List<PaymentMethod> = emptyList(),
    val projects: List<FinanceProject> = emptyList(),
    val financeCategories: List<FinanceCategory> = emptyList(),
    val transactions: List<BankTransaction> = emptyList(),
)

// ---- Server-computed analytics (client no longer computes these) ----

@Serializable
data class VatReturnRate(val rate: Double = 0.0, val net: Double = 0.0, val vat: Double = 0.0)

@Serializable
data class VatReturnQuarter(val q: Int = 0, val net: Double = 0.0, val vat: Double = 0.0)

@Serializable
data class VatReturn(
    val year: Int = 0,
    val net: Double = 0.0,
    val vat: Double = 0.0,
    val gross: Double = 0.0,
    val count: Int = 0,
    val byRate: List<VatReturnRate> = emptyList(),
    val quarters: List<VatReturnQuarter> = emptyList(),
)

@Serializable
data class FinanceKpis(
    val year: Int = 0,
    val net: Double = 0.0,
    val count: Int = 0,
    val avg: Double = 0.0,
    val customers: Int = 0,
    val prevNet: Double = 0.0,
    val growthPct: Double? = null,
)

@Serializable
data class RevenueByCustomer(
    val name: String = "",
    val net: Double = 0.0,
    val gross: Double = 0.0,
    val count: Int = 0,
)

@Serializable
data class MonthRevenue(val month: Int = 0, val net: Double = 0.0)

/** Aging of OPEN invoices (status=sent, untrashed) by days past due. Detail buckets ignored here. */
@Serializable
data class InvoiceAging(
    val openCount: Int = 0,
    val openGross: Double = 0.0,
)

@Serializable
data class FinanceReports(
    val year: Int = 0,
    val years: List<Int> = emptyList(),
    val currentVat: VatReturn = VatReturn(),
    val vat: VatReturn = VatReturn(),
    val kpis: FinanceKpis = FinanceKpis(),
    val customers: List<RevenueByCustomer> = emptyList(),
    val months: List<MonthRevenue> = emptyList(),
    val aging: InvoiceAging = InvoiceAging(),
)

@Serializable
data class VatRateBucket(val rate: String = "", val net: Double = 0.0, val vat: Double = 0.0)

@Serializable
data class AccountVatSummary(
    val income: List<VatRateBucket> = emptyList(),
    val expense: List<VatRateBucket> = emptyList(),
    val outputVat: Double = 0.0,
    val inputVat: Double = 0.0,
    val payable: Double = 0.0,
    val privateSum: Double = 0.0,
    val undecided: Int = 0,
)

@Serializable
data class DuplicateGroup(
    val reason: String = "",
    val key: String = "",
    val ids: List<Int> = emptyList(),
)

@Serializable
data class FinanceDuplicates(
    val invoices: List<DuplicateGroup> = emptyList(),
    val transactions: List<DuplicateGroup> = emptyList(),
)

@Serializable
data class CategorySuggestion(
    @SerialName("tx_id") val txId: Int = 0,
    val merchant: String = "",
    @SerialName("suggested_category") val suggestedCategory: String = "",
)

// ---- Tax reports (server-computed, v1.528) ----

@Serializable
data class VatAdvanceRate(
    val rate: Double = 0.0,
    val outputNet: Double = 0.0,
    val outputVat: Double = 0.0,
    val inputNet: Double = 0.0,
    val inputVat: Double = 0.0,
)

/** Unified USt-Voranmeldung (Zahllast); §19 Kleinunternehmer → outputVat 0. */
@Serializable
data class VatAdvanceReturn(
    val year: Int = 0,
    val quarter: Int? = null,
    val net: Double = 0.0,
    val outputVat: Double = 0.0,
    val inputVat: Double = 0.0,
    val payable: Double = 0.0,
    val byRate: List<VatAdvanceRate> = emptyList(),
    @SerialName("small_business") val smallBusiness: Boolean = false,
)

@Serializable
data class EuerBucket(val name: String = "", val amount: Double = 0.0)

@Serializable
data class EuerSide(val total: Double = 0.0, val byCategory: List<EuerBucket> = emptyList())

/** Simplified EÜR: income − expenses = profit. */
@Serializable
data class EuerReport(
    val year: Int = 0,
    val income: EuerSide = EuerSide(),
    val expenses: EuerSide = EuerSide(),
    val profit: Double = 0.0,
    @SerialName("small_business") val smallBusiness: Boolean = false,
)
