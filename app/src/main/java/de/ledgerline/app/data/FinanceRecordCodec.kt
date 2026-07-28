package de.ledgerline.app.data

import de.ledgerline.app.data.remote.dto.CompanyDto
import de.ledgerline.app.domain.model.CompanyProfile
import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.InvoiceCustomer
import de.ledgerline.app.domain.model.InvoiceLine
import de.ledgerline.app.domain.model.InvoiceStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decodes invoice records from the sealed `/invoices/store` (and the company profile DTO) into the
 * typed model, capturing each record's raw [JsonObject] so a future write re-emits every web/iOS
 * field byte-exact (no data loss) — the same raw-overlay strategy as the other modules. Encode is
 * intentionally not yet implemented: Android's Finance is read-only until the multi-collection
 * sharded write (paymentMethods + transactions must be preserved) lands.
 */
object FinanceRecordCodec {

    fun decodeInvoice(o: JsonObject): Invoice? {
        val id = o.str("id") ?: return null
        return Invoice(
            id = id,
            number = o.str("number"),
            seq = o["seq"]?.jsonPrimitive?.intOrNull,
            status = InvoiceStatus.from(o.str("status")),
            issueDate = o.str("issueDate") ?: "",
            dueDate = o.str("dueDate") ?: "",
            currency = o.str("currency") ?: "EUR",
            lang = o.str("lang") ?: "de",
            customer = (o["customer"] as? JsonObject)?.let(::decodeCustomer) ?: InvoiceCustomer(),
            lines = (o["lines"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodeLine) },
            note = o.str("note") ?: "",
            footer = o.str("footer") ?: "",
            trashed = o["trashed"]?.truthy() ?: false,
            updated = o.str("updated"),
            raw = o,
        )
    }

    private fun decodeCustomer(o: JsonObject) = InvoiceCustomer(
        name = o.str("name") ?: "",
        attn = o.str("attn") ?: "",
        address = o.str("address") ?: "",
        email = o.str("email") ?: "",
        vatId = o.str("vatId") ?: "",
        contactId = o.str("contactId"),
        raw = o,
    )

    private fun decodeLine(o: JsonObject) = InvoiceLine(
        desc = o.str("desc") ?: "",
        qty = o["qty"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        unit = o.str("unit") ?: "",
        unitPrice = o["unitPrice"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        vatRate = o["vatRate"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        raw = o,
    )

    fun companyFrom(dto: CompanyDto): CompanyProfile = CompanyProfile(
        name = dto.name.orEmpty(),
        address = dto.address.orEmpty(),
        email = dto.email.orEmpty(),
        phone = dto.phone.orEmpty(),
        vatId = dto.vatId.orEmpty(),
        taxNumber = dto.taxNumber.orEmpty(),
        iban = dto.iban.orEmpty(),
        currency = dto.currency ?: "EUR",
        paymentTermsDays = dto.paymentTermsDays ?: 14,
        footerText = dto.footerText.orEmpty(),
    )

    // ---- helpers ----
    private fun JsonObject.str(key: String): String? =
        this[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }?.takeIf { it.isNotEmpty() }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    private fun kotlinx.serialization.json.JsonElement.truthy(): Boolean {
        if (this is JsonNull) return false
        val p = jsonPrimitive
        p.booleanOrNull?.let { return it }
        val s = p.contentOrNull ?: return false
        return s.isNotEmpty() && s != "false" && s != "0"
    }
}
