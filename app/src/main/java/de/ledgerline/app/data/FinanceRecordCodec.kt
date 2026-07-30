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
        applyTrashed(out, inv.trashed, inv.raw["trashed"])
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

    // ---- payment methods (decode + encode, raw overlay = no field loss) ----

    fun decodePaymentMethod(o: JsonObject): de.ledgerline.app.domain.model.PaymentMethod? {
        val id = o.str("id") ?: return null
        return de.ledgerline.app.domain.model.PaymentMethod(
            id = id,
            type = o.str("type") ?: "bank",
            label = o.str("label") ?: "",
            holder = o.str("holder") ?: "",
            iban = o.str("iban") ?: "",
            bic = o.str("bic") ?: "",
            bankName = o.str("bankName") ?: "",
            accountNumber = o.str("accountNumber") ?: "",
            url = o.str("url") ?: "",
            cardNetwork = o.str("cardNetwork") ?: "visa",
            cardNumber = o.str("cardNumber") ?: "",
            cardExpiry = o.str("cardExpiry") ?: "",
            email = o.str("email") ?: "",
            note = o.str("note") ?: "",
            business = o["business"]?.truthy() ?: false,
            trashed = o["trashed"]?.truthy() ?: false,
            raw = o,
        )
    }

    fun encodePaymentMethod(pm: de.ledgerline.app.domain.model.PaymentMethod): JsonObject {
        val out = pm.raw.toMutableMap()
        out["id"] = JsonPrimitive(pm.id)
        out["type"] = JsonPrimitive(pm.type)
        out["label"] = JsonPrimitive(pm.label)
        out["holder"] = JsonPrimitive(pm.holder)
        out["iban"] = JsonPrimitive(pm.iban)
        out["bic"] = JsonPrimitive(pm.bic)
        out["bankName"] = JsonPrimitive(pm.bankName)
        out["accountNumber"] = JsonPrimitive(pm.accountNumber)
        out["url"] = JsonPrimitive(pm.url)
        out["cardNetwork"] = JsonPrimitive(pm.cardNetwork)
        out["cardNumber"] = JsonPrimitive(pm.cardNumber)
        out["cardExpiry"] = JsonPrimitive(pm.cardExpiry)
        out["email"] = JsonPrimitive(pm.email)
        out["note"] = JsonPrimitive(pm.note)
        out["business"] = JsonPrimitive(pm.business)
        applyTrashed(out, pm.trashed, pm.raw["trashed"])
        return JsonObject(out)
    }

    // ---- transactions (decode + encode, raw overlay = no field loss) ----

    fun encodeTransaction(t: de.ledgerline.app.domain.model.Transaction): JsonObject {
        val out = t.raw.toMutableMap()
        out["id"] = JsonPrimitive(t.id)
        out["account"] = JsonPrimitive(t.account)
        out["date"] = JsonPrimitive(t.date)
        out["amount"] = numToken(t.amount)
        out["currency"] = JsonPrimitive(t.currency)
        out["counterparty"] = JsonPrimitive(t.counterparty)
        out["purpose"] = JsonPrimitive(t.purpose)
        out["vatCat"] = JsonPrimitive(t.vatCat)
        setOrNull(out, "invoiceId", t.invoiceId?.let { JsonPrimitive(it) })
        applyTrashed(out, t.trashed, t.raw["trashed"])
        return JsonObject(out)
    }

    fun decodeTransaction(o: JsonObject): de.ledgerline.app.domain.model.Transaction? {
        val id = o.str("id") ?: return null
        return de.ledgerline.app.domain.model.Transaction(
            id = id,
            account = o.str("account") ?: "",
            date = o.str("date") ?: "",
            amount = o["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            currency = o.str("currency") ?: "EUR",
            counterparty = o.str("counterparty") ?: "",
            purpose = o.str("purpose") ?: "",
            vatCat = o.str("vatCat") ?: "",
            invoiceId = o.str("invoiceId"),
            trashed = o["trashed"]?.truthy() ?: false,
            raw = o,
        )
    }

    // ---- projects + expenses (decode + encode, raw overlay = no field loss) ----

    fun decodeProject(o: JsonObject): de.ledgerline.app.domain.model.Project? {
        val id = o.str("id") ?: return null
        return de.ledgerline.app.domain.model.Project(
            id = id,
            name = o.str("name") ?: "",
            parentId = o.str("parentId"),
            note = o.str("note") ?: "",
            kind = o.str("kind") ?: "business",
            expenses = (o["expenses"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodeExpense) },
            created = o.str("created") ?: "",
            raw = o,
        )
    }

    private fun decodeExpense(o: JsonObject) = de.ledgerline.app.domain.model.ProjectExpense(
        id = o.str("id") ?: "",
        amount = o["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        date = o.str("date") ?: "",
        note = o.str("note") ?: "",
        account = o.str("account"),
        category = o.str("category") ?: "",
        raw = o,
    )

    fun encodeProject(p: de.ledgerline.app.domain.model.Project): JsonObject {
        val out = p.raw.toMutableMap()
        out["id"] = JsonPrimitive(p.id)
        out["name"] = JsonPrimitive(p.name)
        setOrNull(out, "parentId", p.parentId?.let { JsonPrimitive(it) } ?: JsonNull)
        out["note"] = JsonPrimitive(p.note)
        out["kind"] = JsonPrimitive(p.kind)
        out["expenses"] = JsonArray(p.expenses.map(::encodeExpense))
        if (p.created.isNotEmpty()) out["created"] = JsonPrimitive(p.created)
        return JsonObject(out)
    }

    private fun encodeExpense(e: de.ledgerline.app.domain.model.ProjectExpense): JsonObject {
        val out = e.raw.toMutableMap()
        out["id"] = JsonPrimitive(e.id)
        out["amount"] = numToken(e.amount)
        out["date"] = JsonPrimitive(e.date)
        out["note"] = JsonPrimitive(e.note)
        setOrNull(out, "account", e.account?.let { JsonPrimitive(it) })
        if (e.category.isNotEmpty() || e.raw.containsKey("category")) out["category"] = JsonPrimitive(e.category)
        return JsonObject(out)
    }

    // ---- partners (decode + encode, raw overlay) ----

    fun decodePartner(o: JsonObject): de.ledgerline.app.domain.model.Partner? {
        val id = o.str("id") ?: return null
        val contacts = (o["contacts"] as? JsonArray).orEmpty().mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            de.ledgerline.app.domain.model.PartnerContact(
                id = c.str("id") ?: "", name = c.str("name") ?: "", email = c.str("email") ?: "",
                phone = c.str("phone") ?: "", role = c.str("role") ?: "",
            )
        }.ifEmpty {
            // Legacy single `contact` string → contacts[0] (web migration, back-compat).
            o.str("contact")?.takeIf { it.isNotBlank() }?.let { listOf(de.ledgerline.app.domain.model.PartnerContact(name = it)) }.orEmpty()
        }
        return de.ledgerline.app.domain.model.Partner(
            id = id, name = o.str("name") ?: "", category = o.str("category") ?: "", note = o.str("note") ?: "",
            url = o.str("url") ?: "", logo = o.str("logo") ?: "", address = o.str("address") ?: "",
            email = o.str("email") ?: "", phone = o.str("phone") ?: "", vatId = o.str("vatId") ?: "",
            contacts = contacts, raw = o,
        )
    }

    fun encodePartner(p: de.ledgerline.app.domain.model.Partner): JsonObject {
        val out = p.raw.toMutableMap()
        out["id"] = JsonPrimitive(p.id)
        out["name"] = JsonPrimitive(p.name)
        out["category"] = JsonPrimitive(p.category)
        out["note"] = JsonPrimitive(p.note)
        setOrNull(out, "url", p.url.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) })
        setOrNull(out, "address", p.address.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) })
        setOrNull(out, "email", p.email.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) })
        setOrNull(out, "phone", p.phone.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) })
        setOrNull(out, "vatId", p.vatId.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) })
        // `logo` is fetched/sealed server/web-side; preserve verbatim from raw (never rewrite here).
        // Contacts: web-shaped array; drop the legacy scalar `contact` once we own contacts[].
        if (p.contacts.isNotEmpty() || p.raw.containsKey("contacts")) {
            out["contacts"] = JsonArray(
                p.contacts.map { ct ->
                    buildMap<String, kotlinx.serialization.json.JsonElement> {
                        if (ct.id.isNotBlank()) put("id", JsonPrimitive(ct.id))
                        put("name", JsonPrimitive(ct.name))
                        if (ct.email.isNotBlank()) put("email", JsonPrimitive(ct.email))
                        if (ct.phone.isNotBlank()) put("phone", JsonPrimitive(ct.phone))
                        if (ct.role.isNotBlank()) put("role", JsonPrimitive(ct.role))
                    }.let { JsonObject(it) }
                },
            )
            out.remove("contact")
        }
        return JsonObject(out)
    }

    /** Decode the inline `tx.receipts[]` of a transaction's raw JSON. */
    fun decodeReceipts(txRaw: JsonObject): List<de.ledgerline.app.domain.model.Receipt> =
        (txRaw["receipts"] as? JsonArray).orEmpty().mapNotNull { el -> (el as? JsonObject)?.let(::decodeReceipt) }

    fun decodeReceipt(o: JsonObject): de.ledgerline.app.domain.model.Receipt? {
        val id = o.str("id") ?: return null
        return de.ledgerline.app.domain.model.Receipt(
            id = id,
            name = o.str("name") ?: "",
            mime = o.str("mime") ?: "application/octet-stream",
            total = o["total"]?.jsonPrimitive?.doubleOrNull,
            projectId = o.str("projectId"),
            partnerId = o.str("partnerId"),
            category = o.str("category") ?: "",
            sig = o.str("sig"),
            blob = o.str("blob") ?: o.str("ref"),
            key = o.str("key"),
            kind = o.str("kind") ?: "",
            raw = o,
        )
    }

    /** Encode a receipt back to JSON (raw-overlay so web/OCR fields survive an Android edit). */
    fun encodeReceipt(r: de.ledgerline.app.domain.model.Receipt): JsonObject {
        val out = r.raw.toMutableMap()
        out["id"] = JsonPrimitive(r.id)
        out["name"] = JsonPrimitive(r.name)
        out["mime"] = JsonPrimitive(r.mime)
        setOrNull(out, "total", r.total?.let { numToken(it) })
        setOrNull(out, "projectId", r.projectId?.let { JsonPrimitive(it) })
        setOrNull(out, "partnerId", r.partnerId?.let { JsonPrimitive(it) })
        if (r.category.isNotEmpty() || r.raw.containsKey("category")) out["category"] = JsonPrimitive(r.category)
        setOrNull(out, "sig", r.sig?.let { JsonPrimitive(it) })
        setOrNull(out, "blob", r.blob?.let { JsonPrimitive(it) })
        setOrNull(out, "key", r.key?.let { JsonPrimitive(it) })
        return JsonObject(out)
    }

    fun companyFrom(dto: CompanyDto): CompanyProfile = CompanyProfile(
        name = dto.name.orEmpty(),
        address = dto.address.orEmpty(),
        email = dto.email.orEmpty(),
        phone = dto.phone.orEmpty(),
        vatId = dto.vatId.orEmpty(),
        taxNumber = dto.taxId.orEmpty(),
        iban = dto.iban.orEmpty(),
        bic = dto.bic.orEmpty(),
        bankName = dto.bankName.orEmpty(),
        currency = "EUR", // the company has no currency field; invoices carry their own
        defaultVatRate = dto.defaultVatRate ?: 19.0,
        paymentTermsDays = dto.paymentTermsDays ?: 14,
        paymentTermsText = dto.paymentTermsText.orEmpty(),
        paymentMethods = dto.paymentMethods.orEmpty(),
        footerText = dto.footerText.orEmpty(),
        numberFormat = dto.numberFormat?.ifBlank { null } ?: "YYYY-NNNN",
        nextNumber = dto.nextNumber ?: 1,
        hasLogo = dto.hasLogo ?: false,
    )

    fun companyToDto(c: CompanyProfile): CompanyDto = CompanyDto(
        name = c.name, address = c.address, email = c.email, phone = c.phone,
        vatId = c.vatId, taxId = c.taxNumber, iban = c.iban, bic = c.bic, bankName = c.bankName,
        defaultVatRate = c.defaultVatRate,
        paymentTermsDays = c.paymentTermsDays, paymentTermsText = c.paymentTermsText,
        paymentMethods = c.paymentMethods, footerText = c.footerText,
        numberFormat = c.numberFormat, nextNumber = c.nextNumber,
    )

    // ---- helpers ----
    private fun JsonObject.str(key: String): String? =
        this[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }?.takeIf { it.isNotEmpty() }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    /**
     * Render `trashed` as web does: `false | ISO-string`, NEVER a boolean — mirrors
     * [FileRecordCodec]/[WorkspaceRecordCodec]. When the flag is unchanged the original raw token
     * (a web ISO timestamp or `null`) is kept verbatim; only a real trash/restore writes a fresh
     * ISO / drops the key. A naive `JsonPrimitive(bool)` would destroy a web-set delete timestamp.
     */
    private fun applyTrashed(
        out: MutableMap<String, kotlinx.serialization.json.JsonElement>,
        trashed: Boolean,
        rawTrashed: kotlinx.serialization.json.JsonElement?,
    ) {
        val wasTrashed = rawTrashed?.truthy() ?: false
        if (trashed == wasTrashed) return // keep the raw token (ISO / null) — no destructive rewrite
        out["trashed"] =
            if (trashed) JsonPrimitive(java.time.Instant.now().toString())
            else kotlinx.serialization.json.JsonNull
    }

    private fun kotlinx.serialization.json.JsonElement.truthy(): Boolean {
        if (this is JsonNull) return false
        val p = jsonPrimitive
        p.booleanOrNull?.let { return it }
        val s = p.contentOrNull ?: return false
        return s.isNotEmpty() && s != "false" && s != "0"
    }
}
