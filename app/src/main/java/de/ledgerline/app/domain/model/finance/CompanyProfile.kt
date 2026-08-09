package de.ledgerline.app.domain.model.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The caller's own business identity + invoice defaults (`GET/PUT /company`). Non-secret, served in
 * the clear. All fields optional; a PUT updates only the fields sent. `has_logo` is response-only.
 */
@Serializable
data class CompanyProfile(
    @SerialName("company_name") val companyName: String? = null,
    @SerialName("company_address") val companyAddress: String? = null,
    @SerialName("company_email") val companyEmail: String? = null,
    @SerialName("company_phone") val companyPhone: String? = null,
    @SerialName("company_tax_id") val companyTaxId: String? = null,
    @SerialName("company_vat_id") val companyVatId: String? = null,
    @SerialName("company_iban") val companyIban: String? = null,
    @SerialName("company_bic") val companyBic: String? = null,
    @SerialName("company_bank_name") val companyBankName: String? = null,
    @SerialName("invoice_number_format") val invoiceNumberFormat: String? = null,
    @SerialName("invoice_next_number") val invoiceNextNumber: Int? = null,
    @SerialName("invoice_default_vat_rate") val invoiceDefaultVatRate: Double? = null,
    @SerialName("invoice_payment_terms_days") val invoicePaymentTermsDays: Int? = null,
    @SerialName("invoice_footer_text") val invoiceFooterText: String? = null,
    @SerialName("invoice_accent_color") val invoiceAccentColor: String? = null,
    @SerialName("invoice_heading_color") val invoiceHeadingColor: String? = null,
    @SerialName("invoice_template") val invoiceTemplate: String? = null,
    @SerialName("invoice_payment_methods") val invoicePaymentMethods: String? = null,
    @SerialName("invoice_payment_terms_text") val invoicePaymentTermsText: String? = null,
    @SerialName("company_website") val companyWebsite: String? = null,
    @SerialName("invoice_font") val invoiceFont: String? = null,
    @SerialName("invoice_vat_ist") val invoiceVatIst: Boolean? = null, // Ist (cash) vs Soll (accrual)
    @SerialName("small_business") val smallBusiness: Boolean? = null, // §19 Kleinunternehmer
    @SerialName("has_logo") val hasLogo: Boolean = false,
)
