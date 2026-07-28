package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Non-secret company profile (business identity + invoice defaults), byte-shape matching the web
 * `Api/CompanyController::present` — flat `company_*` / `invoice_*` snake-case keys. The GET/PUT
 * responses WRAP it in `{ "company": { … } }` ([CompanyResponse]); the PUT body is the flat object.
 */
@Serializable
data class CompanyDto(
    @SerialName("company_name") val name: String? = null,
    @SerialName("company_address") val address: String? = null,
    @SerialName("company_email") val email: String? = null,
    @SerialName("company_phone") val phone: String? = null,
    @SerialName("company_vat_id") val vatId: String? = null,
    @SerialName("company_tax_id") val taxId: String? = null,
    @SerialName("company_iban") val iban: String? = null,
    @SerialName("company_bic") val bic: String? = null,
    @SerialName("company_bank_name") val bankName: String? = null,
    @SerialName("invoice_number_format") val numberFormat: String? = null,
    @SerialName("invoice_next_number") val nextNumber: Int? = null,
    @SerialName("invoice_payment_terms_days") val paymentTermsDays: Int? = null,
    @SerialName("invoice_footer_text") val footerText: String? = null,
    @SerialName("invoice_default_vat_rate") val defaultVatRate: Double? = null,
    @SerialName("invoice_payment_methods") val paymentMethods: String? = null,
    @SerialName("invoice_payment_terms_text") val paymentTermsText: String? = null,
    @SerialName("has_logo") val hasLogo: Boolean? = null,
)

/** GET/PUT `/company` wrapper: `{ "company": { … } }`. */
@Serializable
data class CompanyResponse(val company: CompanyDto? = null)

/**
 * `POST /api/v1/invoices/ocr` response — server-side OCR of a receipt (transient cleartext, ZK-parity
 * with `/gallery/process`). `text` is line-structured; recognition runs client-side (`ReceiptOcr`).
 * See docs spec `ledgerline-server-ocr-spec.md`. Optional endpoint: absent → manual entry.
 */
@Serializable
data class OcrResponse(
    val text: String = "",
    val source: String? = null,   // "pdf-text" | "ocr"
    val pages: Int? = null,
)
