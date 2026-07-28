package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Non-secret company profile (business identity for invoices), from `GET /company`. All fields
 * optional/tolerant — the server may omit or add keys. Snake-case wire names mapped explicitly.
 */
@Serializable
data class CompanyDto(
    val name: String? = null,
    val address: String? = null,
    val email: String? = null,
    val phone: String? = null,
    @SerialName("vat_id") val vatId: String? = null,
    @SerialName("tax_number") val taxNumber: String? = null,
    val iban: String? = null,
    val currency: String? = null,
    @SerialName("payment_terms_days") val paymentTermsDays: Int? = null,
    @SerialName("footer_text") val footerText: String? = null,
    @SerialName("number_format") val numberFormat: String? = null,
    @SerialName("next_number") val nextNumber: Int? = null,
)
