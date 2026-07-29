package de.ledgerline.app.ui.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.FinanceCache
import de.ledgerline.app.core.finance.InvoiceMath
import de.ledgerline.app.core.security.VaultKeyHolder
import de.ledgerline.app.data.FinanceRepository
import de.ledgerline.app.domain.model.CompanyProfile
import de.ledgerline.app.domain.model.Invoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import javax.inject.Inject

/**
 * Drives the (read-only) Finance module: the sealed invoices from [FinanceCache] + the non-secret
 * company profile. Grouped/filtered by year with paid/outstanding KPIs. Write (create/edit) is not
 * yet available — the multi-collection sharded write must land first (see [FinanceRepository]).
 */
@HiltViewModel
class FinanceViewModel @Inject constructor(
    private val repo: FinanceRepository,
    cache: FinanceCache,
    vaultKeyHolder: VaultKeyHolder,
) : ViewModel() {

    val invoices: StateFlow<List<Invoice>> =
        cache.value.map { it?.manifest?.invoices ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val paymentMethods: StateFlow<List<de.ledgerline.app.domain.model.PaymentMethod>> =
        cache.value.map { it?.manifest?.paymentMethods ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val transactions: StateFlow<List<de.ledgerline.app.domain.model.Transaction>> =
        cache.value.map { it?.manifest?.transactions ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val projects: StateFlow<List<de.ledgerline.app.domain.model.Project>> =
        cache.value.map { it?.manifest?.projects ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val partners: StateFlow<List<de.ledgerline.app.domain.model.Partner>> =
        cache.value.map { it?.manifest?.partners ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val company: StateFlow<CompanyProfile?> = cache.company
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _year = MutableStateFlow(java.time.LocalDate.now().year)
    val year: StateFlow<Int> = _year.asStateFlow()

    // Global business/private scope filtering every finance tab (web `financeScope`). `private` =
    // a private booking (vatCat 'private'), a non-business payment method, a private project, or a
    // receipt on any of those; invoices are always business (→ empty in the private scope).
    private val _scope = MutableStateFlow("all")   // all | business | private
    val financeScope: StateFlow<String> = _scope.asStateFlow()
    fun setFinanceScope(s: String) { _scope.value = s }

    private fun scopeMatch(isPrivate: Boolean): Boolean {
        val s = _scope.value
        return s == "all" || (s == "private") == isPrivate
    }
    private fun pmPrivate(pm: de.ledgerline.app.domain.model.PaymentMethod) = !pm.business
    private fun txPrivate(tx: de.ledgerline.app.domain.model.Transaction) = tx.vatCat == "private"

    init {
        // Load once the vault is unlocked (the VM can init before the key lands).
        viewModelScope.launch {
            vaultKeyHolder.unlocked.collect { unlocked -> if (unlocked) load() }
        }
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            repo.load()
            _loading.value = false
        }
    }

    fun setYear(y: Int) { _year.value = y }

    /** The years that have invoices, newest first (for the year selector). */
    fun years(): List<Int> =
        invoices.value.mapNotNull { InvoiceMath.invoiceYear(it).toIntOrNull() }.distinct().sortedDescending()

    /** Non-trashed invoices of [y], newest first. Invoices are business → empty in the private scope. */
    fun invoicesOf(y: Int): List<Invoice> =
        if (_scope.value == "private") emptyList()
        else invoices.value.filter { !it.trashed && InvoiceMath.invoiceYear(it) == y.toString() }
            .sortedByDescending { it.issueDate }

    fun kpis(y: Int): InvoiceMath.YearKpis = InvoiceMath.yearKpis(invoices.value, y)

    // ---- statistics (VAT return / revenue analytics for [y]) ----
    fun vatReturn(y: Int) = de.ledgerline.app.core.finance.FinanceStats.vatReturn(invoices.value, y)
    fun revenueByCustomer(y: Int) = de.ledgerline.app.core.finance.FinanceStats.revenueByCustomer(invoices.value, y)
    fun monthlyRevenue(y: Int) = de.ledgerline.app.core.finance.FinanceStats.monthlyRevenue(invoices.value, y)
    fun statsKpis(y: Int) = de.ledgerline.app.core.finance.FinanceStats.statsKpis(invoices.value, y)

    /** Output/input VAT (USt-Zahllast) from the year [y]'s bookings across all accounts. */
    fun accountVat(y: Int) = de.ledgerline.app.core.finance.FinanceStats.accountVatSummary(
        transactions.value.filter { it.date.take(4) == y.toString() },
    )

    fun invoiceById(id: String): Invoice? = invoices.value.firstOrNull { it.id == id }

    fun totals(inv: Invoice) = InvoiceMath.totals(inv)

    // ---- write ----

    /** A blank draft with the company defaults + one empty line (web `newInvoice`). */
    fun newDraft(): Invoice {
        val c = company.value
        val today = java.time.LocalDate.now()
        val due = today.plusDays((c?.paymentTermsDays ?: 14).toLong())
        return Invoice(
            id = de.ledgerline.app.core.Ids.newId(),
            status = de.ledgerline.app.domain.model.InvoiceStatus.DRAFT,
            issueDate = today.toString(),
            dueDate = due.toString(),
            currency = c?.currency ?: "EUR",
            note = c?.paymentTermsText.orEmpty(),
            footer = c?.footerText.orEmpty(),
            lines = listOf(de.ledgerline.app.domain.model.InvoiceLine(vatRate = c?.defaultVatRate ?: 19.0)),
        )
    }

    /** Build a draft invoice from a parsed e-invoice XML (user reviews it in the editor before saving). */
    fun invoiceFromEInvoice(p: de.ledgerline.app.core.finance.EInvoiceXml.ParsedEInvoice): Invoice = Invoice(
        id = de.ledgerline.app.core.Ids.newId(),
        number = p.number,
        status = de.ledgerline.app.domain.model.InvoiceStatus.DRAFT,
        issueDate = p.issueDate ?: java.time.LocalDate.now().toString(),
        dueDate = p.dueDate ?: "",
        currency = p.currency,
        customer = de.ledgerline.app.domain.model.InvoiceCustomer(
            name = p.customer.name, address = p.customer.address, vatId = p.customer.vatId, email = p.customer.email,
        ),
        lines = p.lines.map {
            de.ledgerline.app.domain.model.InvoiceLine(desc = it.desc, qty = it.qty, unit = it.unit, unitPrice = it.unitPrice, vatRate = it.vatRate)
        }.ifEmpty { listOf(de.ledgerline.app.domain.model.InvoiceLine()) },
    )

    /** Build a draft invoice from OCR'd receipt/invoice text (fields best-effort; user reviews). */
    fun invoiceFromOcrText(text: String): Invoice {
        val a = de.ledgerline.app.core.finance.ReceiptOcr.analyze(text)
        return Invoice(
            id = de.ledgerline.app.core.Ids.newId(),
            number = a.number.ifBlank { null },
            status = de.ledgerline.app.domain.model.InvoiceStatus.DRAFT,
            issueDate = a.date.ifBlank { java.time.LocalDate.now().toString() },
            currency = a.currency.ifBlank { "EUR" },
            customer = de.ledgerline.app.domain.model.InvoiceCustomer(name = a.merchant),
            lines = listOf(de.ledgerline.app.domain.model.InvoiceLine(
                desc = a.merchant.ifBlank { "Rechnung" }, qty = 1.0,
                unitPrice = a.total ?: 0.0, vatRate = a.vat.toDoubleOrNull() ?: 0.0,
            )),
        )
    }

    /**
     * Import a document as an invoice draft: an embedded/standalone e-invoice XML is parsed exactly
     * ([EInvoiceXml]); a PDF/image is OCR'd on the server and its fields recognised ([ReceiptOcr]).
     * [onResult] gets the draft (never auto-saved) or null if nothing usable.
     */
    fun importInvoiceDocument(bytes: ByteArray, name: String, mime: String, onResult: (Invoice?) -> Unit) {
        viewModelScope.launch {
            val asText = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: ""
            if (de.ledgerline.app.core.finance.EInvoiceXml.looksLikeEInvoiceXml(asText)) {
                onResult(de.ledgerline.app.core.finance.EInvoiceXml.parse(asText)?.let(::invoiceFromEInvoice)); return@launch
            }
            val isPdfOrImg = mime.contains("pdf", true) || mime.startsWith("image/") || name.endsWith(".pdf", true)
            if (isPdfOrImg) {
                val text = repo.ocrDocument(bytes, name, mime)
                onResult(text?.let { invoiceFromOcrText(it) }); return@launch
            }
            onResult(null)
        }
    }

    /** Insert or update [inv] in the store; [onDone] gets whether it persisted. */
    fun save(inv: Invoice, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val res = repo.save { list ->
                if (list.any { it.id == inv.id }) list.map { if (it.id == inv.id) inv else it }
                else listOf(inv) + list
            }
            onDone(res is de.ledgerline.app.core.Outcome.Ok)
        }
    }

    /**
     * Issue a draft: assign a gapless GoBD number (per-year seq → template) and mark it `sent`. The
     * seq derives from the current invoices + the company floor, so it never collides with another
     * device's number after the store's 409-rebase.
     */
    fun issue(inv: Invoice, onDone: (Boolean) -> Unit = {}) {
        val c = company.value
        val year = InvoiceMath.invoiceYear(inv).ifBlank { java.time.LocalDate.now().year.toString() }
        val seq = InvoiceMath.nextSeqForYear(invoices.value, year, c?.nextNumber ?: 1)
        val number = InvoiceMath.formatNumber(c?.numberFormat, seq, inv.issueDate)
        save(inv.copy(seq = seq, number = number, status = de.ledgerline.app.domain.model.InvoiceStatus.SENT), onDone)
    }

    fun setStatus(inv: Invoice, status: de.ledgerline.app.domain.model.InvoiceStatus, onDone: (Boolean) -> Unit = {}) =
        save(inv.copy(status = status), onDone)

    fun trash(inv: Invoice, onDone: (Boolean) -> Unit = {}) = save(inv.copy(trashed = true), onDone)

    fun saveCompany(profile: CompanyProfile, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onDone(repo.saveCompany(profile)) }
    }

    /** Fetch the company logo image bytes (or null) — for the company editor preview. */
    fun loadCompanyLogo(onResult: (ByteArray?) -> Unit) {
        viewModelScope.launch { onResult(repo.companyLogo()) }
    }

    // ---- payment methods ----
    fun sortedPaymentMethods(): List<de.ledgerline.app.domain.model.PaymentMethod> =
        de.ledgerline.app.core.finance.PaymentMethods.sorted(paymentMethods.value)

    /** Payment methods filtered by the global scope (non-business = private). */
    fun scopedPaymentMethods(): List<de.ledgerline.app.domain.model.PaymentMethod> =
        sortedPaymentMethods().filter { scopeMatch(pmPrivate(it)) }

    /** Mark [pm] the single business account (only one at a time; toggles it off if already set). */
    fun toggleBusinessAccount(pm: de.ledgerline.app.domain.model.PaymentMethod, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val on = !pm.business
            val res = repo.savePaymentMethods { list -> list.map { it.copy(business = it.id == pm.id && on) } }
            onDone(res is de.ledgerline.app.core.Outcome.Ok)
        }
    }

    fun paymentMethodById(id: String): de.ledgerline.app.domain.model.PaymentMethod? =
        paymentMethods.value.firstOrNull { it.id == id }

    fun newPaymentMethod(type: String = "bank"): de.ledgerline.app.domain.model.PaymentMethod =
        de.ledgerline.app.core.finance.PaymentMethods.blank(de.ledgerline.app.core.Ids.newId(), type)

    /** Booking count + signed balance (income − expenses) for a payment method [id]. */
    fun accountBalance(id: String): Double =
        transactions.value.filter { it.account == id && !it.trashed }.sumOf { it.amount }

    fun accountTransactions(id: String): List<de.ledgerline.app.domain.model.Transaction> =
        transactions.value.filter { it.account == id && !it.trashed }.sortedByDescending { it.date }

    fun savePaymentMethod(pm: de.ledgerline.app.domain.model.PaymentMethod, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val res = repo.savePaymentMethods { list ->
                val next = if (list.any { it.id == pm.id }) list.map { if (it.id == pm.id) pm else it } else listOf(pm) + list
                // Single business account: if this one is business, clear it on every other.
                if (pm.business) next.map { it.copy(business = it.id == pm.id) } else next
            }
            onDone(res is de.ledgerline.app.core.Outcome.Ok)
        }
    }

    fun trashPaymentMethod(pm: de.ledgerline.app.domain.model.PaymentMethod, onDone: (Boolean) -> Unit = {}) =
        savePaymentMethod(pm.copy(trashed = true), onDone)

    private val bankIconCache = java.util.concurrent.ConcurrentHashMap<String, androidx.compose.ui.graphics.ImageBitmap>()
    private val bankIconTried = java.util.Collections.synchronizedSet(HashSet<String>())

    /** The bank/site logo for a payment method's website [pm.url], or null → fall back to the glyph. */
    suspend fun bankIconFor(pm: de.ledgerline.app.domain.model.PaymentMethod): androidx.compose.ui.graphics.ImageBitmap? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val host = de.ledgerline.app.core.autofill.DomainMatch.normalizeHost(pm.url) ?: return@withContext null
            bankIconCache[host]?.let { return@withContext it }
            if (!bankIconTried.add(host)) return@withContext null
            val bmp = repo.fetchIcon(host)?.let { de.ledgerline.app.core.passwords.Favicons.decode(it) }
            if (bmp != null) bankIconCache[host] = bmp
            bmp
        }

    // ---- transactions (manual edit + statement import) ----
    fun newTransaction(account: String) = de.ledgerline.app.domain.model.Transaction(
        id = de.ledgerline.app.core.Ids.newId(), account = account, date = java.time.LocalDate.now().toString(),
    )

    fun saveTransaction(t: de.ledgerline.app.domain.model.Transaction, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val res = repo.saveTransactions { list ->
                if (list.any { it.id == t.id }) list.map { if (it.id == t.id) t else it } else listOf(t) + list
            }
            onDone(res is de.ledgerline.app.core.Outcome.Ok)
        }
    }

    fun trashTransaction(t: de.ledgerline.app.domain.model.Transaction, onDone: (Boolean) -> Unit = {}) =
        saveTransaction(t.copy(trashed = true), onDone)

    // ---- receipts (documents attached to a booking; ZK content blobs) ----
    fun receiptsOf(tx: de.ledgerline.app.domain.model.Transaction): List<de.ledgerline.app.domain.model.Receipt> =
        de.ledgerline.app.data.FinanceRecordCodec.decodeReceipts(tx.raw)

    private fun de.ledgerline.app.domain.model.Transaction.withReceipts(receipts: List<de.ledgerline.app.domain.model.Receipt>): de.ledgerline.app.domain.model.Transaction {
        val arr = kotlinx.serialization.json.JsonArray(receipts.map { de.ledgerline.app.data.FinanceRecordCodec.encodeReceipt(it) })
        return copy(raw = kotlinx.serialization.json.JsonObject(raw + ("receipts" to arr)))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Attach a document to [tx]: dedupe by content hash, encrypt+upload, store the receipt inline.
     *  [onDone]: true = added, false = failed, null = duplicate (already attached). */
    fun attachReceipt(tx: de.ledgerline.app.domain.model.Transaction, bytes: ByteArray, name: String, mime: String, onDone: (Boolean?) -> Unit) {
        viewModelScope.launch {
            val sig = sha256Hex(bytes)
            val current = receiptsOf(tx)
            if (current.any { it.sig == sig }) { onDone(null); return@launch }
            val up = repo.uploadReceiptDocument(bytes) ?: run { onDone(false); return@launch }
            var r = de.ledgerline.app.domain.model.Receipt(
                id = de.ledgerline.app.core.Ids.newId(), name = name, mime = mime, sig = sig, blob = up.first, key = up.second,
            )
            // Best-effort server-side OCR → pre-fill fields + auto-name + partner link + learned category.
            repo.ocrDocument(bytes, name, mime)?.let { text ->
                val a = de.ledgerline.app.core.finance.ReceiptOcr.analyze(text)
                val merchant = a.merchant.ifBlank { tx.counterparty }
                val plist = partners.value
                val learned = de.ledgerline.app.core.finance.ReceiptEnrich.learnedCategoryFor(plist, merchant)
                val ext = name.substringAfterLast('.', "")
                r = r.copy(
                    name = de.ledgerline.app.core.finance.ReceiptEnrich.buildReceiptName(a.date, merchant, a.number, ext),
                    total = a.total,
                    category = learned.ifBlank { a.category },
                    partnerId = de.ledgerline.app.core.finance.ReceiptEnrich.matchPartner(plist, merchant)?.id,
                )
            }
            saveTransaction(tx.withReceipts(current + r)) { ok -> onDone(if (ok) true else false) }
        }
    }

    /** Fetch a receipt's decrypted document bytes for the in-app viewer (never written to disk here). */
    fun loadReceipt(r: de.ledgerline.app.domain.model.Receipt, onResult: (ByteArray?) -> Unit) {
        val blob = r.blob; val key = r.key
        if (blob == null || key == null) { onResult(null); return }
        viewModelScope.launch { onResult(repo.downloadReceipt(blob, key)) }
    }

    /** Suspend variant of [loadReceipt] for the in-app viewer (decode off the main thread). */
    suspend fun loadReceiptBytes(r: de.ledgerline.app.domain.model.Receipt): ByteArray? {
        val blob = r.blob ?: return null
        val key = r.key ?: return null
        return repo.downloadReceipt(blob, key)
    }

    fun updateReceipt(tx: de.ledgerline.app.domain.model.Transaction, r: de.ledgerline.app.domain.model.Receipt, onDone: (Boolean) -> Unit = {}) =
        saveTransaction(tx.withReceipts(receiptsOf(tx).map { if (it.id == r.id) r else it }), onDone)

    fun deleteReceipt(tx: de.ledgerline.app.domain.model.Transaction, r: de.ledgerline.app.domain.model.Receipt, onDone: (Boolean) -> Unit = {}) =
        saveTransaction(tx.withReceipts(receiptsOf(tx).filter { it.id != r.id }), onDone)

    // ---- cost projects ----
    private val FP = de.ledgerline.app.core.finance.FinanceProjects

    /** All receipts (inline on transactions) paired with their booking, for project totals. */
    fun allReceipts(): List<de.ledgerline.app.core.finance.FinanceProjects.ReceiptRef> =
        transactions.value.filter { !it.trashed }.flatMap { tx ->
            de.ledgerline.app.data.FinanceRecordCodec.decodeReceipts(tx.raw)
                .map { de.ledgerline.app.core.finance.FinanceProjects.ReceiptRef(it, tx) }
        }

    fun projectTree() = FP.projectTree(projects.value)
    /** The project tree filtered by the global business/private scope (via effective kind). */
    fun scopedProjectTree() = projectTree().filter { scopeMatch(effectiveKind(it.project.id) == "private") }
    fun projectById(id: String) = projects.value.firstOrNull { it.id == id }
    fun projectRolledTotal(id: String) = FP.rolledTotal(projects.value, id, allReceipts())
    fun projectOwnTotal(p: de.ledgerline.app.domain.model.Project) = FP.ownTotal(p, allReceipts())
    /** A project's effective kind (derived from the root ancestor; a sub-project can't differ). */
    fun effectiveKind(id: String) = FP.effectiveKind(projects.value, id)

    /** Own-total cost split business vs private across all projects (for the stats + project cards). */
    fun projectScopeTotals(): Pair<Double, Double> {
        var business = 0.0; var priv = 0.0
        for (p in projects.value) { val t = projectOwnTotal(p); if (effectiveKind(p.id) == "private") priv += t else business += t }
        return (Math.round(business * 100) / 100.0) to (Math.round(priv * 100) / 100.0)
    }

    /** New project; a sub-project inherits its parent's effective kind (locked, web parity). */
    fun newProject(parentId: String?, kind: String = "business") = de.ledgerline.app.domain.model.Project(
        id = de.ledgerline.app.core.Ids.newId(), parentId = parentId,
        kind = if (parentId != null) effectiveKind(parentId) else kind,
        created = java.time.LocalDate.now().toString(),
    )

    fun saveProject(p: de.ledgerline.app.domain.model.Project, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val res = repo.saveProjects { list ->
                val next = if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else listOf(p) + list
                FP.normalizeKinds(next)   // force every sub-project to its root's kind
            }
            onDone(res is de.ledgerline.app.core.Outcome.Ok)
        }
    }

    /** Delete a project and all its descendants (bundled receipts are kept, just un-referenced). */
    fun deleteProject(p: de.ledgerline.app.domain.model.Project, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val res = repo.saveProjects { list ->
                val kill = (FP.descendantIds(list, p.id) + p.id).toSet()
                list.filter { it.id !in kill }
            }
            onDone(res is de.ledgerline.app.core.Outcome.Ok)
        }
    }

    fun money2(value: Double): String = money(value, null)

    // ---- business partners ----
    fun sortedPartners() = partners.value.sortedBy { it.name.lowercase() }
    fun partnerById(id: String) = partners.value.firstOrNull { it.id == id }
    fun newPartner() = de.ledgerline.app.domain.model.Partner(id = de.ledgerline.app.core.Ids.newId())

    fun savePartner(p: de.ledgerline.app.domain.model.Partner, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val res = repo.savePartners { list ->
                if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else listOf(p) + list
            }
            onDone(res is de.ledgerline.app.core.Outcome.Ok)
        }
    }

    fun deletePartner(p: de.ledgerline.app.domain.model.Partner, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val res = repo.savePartners { list -> list.filter { it.id != p.id } }
            onDone(res is de.ledgerline.app.core.Outcome.Ok)
        }
    }

    /**
     * Import parsed statement lines into [accountId], deduped against that account's existing bookings
     * and (default) auto-linked to the invoices they settle. [onDone] gets `(added, matched)`; a save
     * failure reports `(-1, 0)`.
     */
    fun importTransactions(parsed: List<de.ledgerline.app.core.finance.BankStatement.ParsedTx>, accountId: String, matchInvoices: Boolean = true, onDone: (Int, Int) -> Unit) {
        viewModelScope.launch {
            when (val res = repo.importStatement(accountId, parsed, matchInvoices)) {
                is de.ledgerline.app.core.Outcome.Ok -> onDone(res.value.added, res.value.matched)
                is de.ledgerline.app.core.Outcome.Err -> onDone(-1, 0)
            }
        }
    }

    /** Currency-format a value with the invoice/company currency (fallback EUR), device locale. */
    fun money(value: Double, currency: String?): String {
        val cur = currency?.takeIf { it.isNotBlank() } ?: company.value?.currency ?: "EUR"
        return try {
            NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
                this.currency = Currency.getInstance(cur)
            }.format(value)
        } catch (_: Exception) {
            "%,.2f %s".format(value, cur)
        }
    }
}
