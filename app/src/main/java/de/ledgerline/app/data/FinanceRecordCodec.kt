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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.floor

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

    // ---- encode (raw overlay, byte-web-compatible for shard hashing) ----

    /**
     * Encode an invoice back to JSON, overlaying only Android-owned fields over the record's raw
     * [Invoice.raw] so unknown web fields survive AND an unchanged invoice re-emits byte-identically
     * (same canonical bytes → same shard hash → dirty-save reuse, no needless re-upload). Numbers use
     * clean integer/decimal tokens like the web.
     */
    fun encodeInvoice(inv: Invoice): JsonObject {
        val out = inv.raw.toMutableMap()
        out["id"] = JsonPrimitive(inv.id)
        setOrNull(out, "number", inv.number?.let { JsonPrimitive(it) })
        setOrNull(out, "seq", inv.seq?.let { JsonPrimitive(it) })
        out["status"] = JsonPrimitive(inv.status.wire)
        out["issueDate"] = JsonPrimitive(inv.issueDate)
        out["dueDate"] = JsonPrimitive(inv.dueDate)
        out["currency"] = JsonPrimitive(inv.currency)
        out["lang"] = JsonPrimitive(inv.lang)
        out["customer"] = encodeCustomer(inv.customer)
        out["lines"] = JsonArray(inv.lines.map(::encodeLine))
        out["note"] = JsonPrimitive(inv.note)
        if (inv.raw.containsKey("footer") || inv.footer.isNotEmpty()) out["footer"] = JsonPrimitive(inv.footer)
        out["trashed"] = JsonPrimitive(inv.trashed)
        inv.updated?.let { out["updated"] = JsonPrimitive(it) }
        return JsonObject(out)
    }

    private fun encodeCustomer(c: de.ledgerline.app.domain.model.InvoiceCustomer): JsonObject {
        val out = c.raw.toMutableMap()
        out["name"] = JsonPrimitive(c.name)
        out["attn"] = JsonPrimitive(c.attn)
        out["address"] = JsonPrimitive(c.address)
        out["email"] = JsonPrimitive(c.email)
        out["vatId"] = JsonPrimitive(c.vatId)
        out["contactId"] = c.contactId?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull
        return JsonObject(out)
    }

    private fun encodeLine(l: InvoiceLine): JsonObject {
        val out = l.raw.toMutableMap()
        out["desc"] = JsonPrimitive(l.desc)
        out["qty"] = numToken(l.qty)
        out["unit"] = JsonPrimitive(l.unit)
        out["unitPrice"] = numToken(l.unitPrice)
        out["vatRate"] = numToken(l.vatRate)
        return JsonObject(out)
    }

    /** Emit an integral value as an integer token (`19`), else a decimal (`900.5`) — web parity. */
    private fun numToken(d: Double): JsonPrimitive =
        if (!d.isInfinite() && !d.isNaN() && d == floor(d) && abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    private fun setOrNull(out: MutableMap<String, JsonElement>, key: String, value: JsonElement?) {
        if (value != null) out[key] = value else out.remove(key)
    }

    fun companyFrom(dto: CompanyDto): CompanyProfile = CompanyProfile(
        name = dto.name.orEmpty(),
        address = dto.address.orEmpty(),
        email = dto.email.orEmpty(),
        phone = dto.phone.orEmpty(),
        vatId = dto.vatId.orEmpty(),
        taxNumber = dto.taxId.orEmpty(),
        iban = dto.iban.orEmpty(),
        currency = "EUR", // the company has no currency field; invoices carry their own
        paymentTermsDays = dto.paymentTermsDays ?: 14,
        footerText = dto.footerText.orEmpty(),
        numberFormat = dto.numberFormat?.ifBlank { null } ?: "YYYY-NNNN",
        nextNumber = dto.nextNumber ?: 1,
    )

    fun companyToDto(c: CompanyProfile): CompanyDto = CompanyDto(
        name = c.name, address = c.address, email = c.email, phone = c.phone,
        vatId = c.vatId, taxId = c.taxNumber, iban = c.iban,
        paymentTermsDays = c.paymentTermsDays, footerText = c.footerText,
        numberFormat = c.numberFormat, nextNumber = c.nextNumber,
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
