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

    val company: StateFlow<CompanyProfile?> = cache.company
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _year = MutableStateFlow(java.time.LocalDate.now().year)
    val year: StateFlow<Int> = _year.asStateFlow()

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

    /** Non-trashed invoices of [y], newest issue date first. */
    fun invoicesOf(y: Int): List<Invoice> =
        invoices.value.filter { !it.trashed && InvoiceMath.invoiceYear(it) == y.toString() }
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
                if (list.any { it.id == pm.id }) list.map { if (it.id == pm.id) pm else it } else listOf(pm) + list
            }
            onDone(res is de.ledgerline.app.core.Outcome.Ok)
        }
    }

    fun trashPaymentMethod(pm: de.ledgerline.app.domain.model.PaymentMethod, onDone: (Boolean) -> Unit = {}) =
        savePaymentMethod(pm.copy(trashed = true), onDone)

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

    // ---- cost projects ----
    private val FP = de.ledgerline.app.core.finance.FinanceProjects

    /** All receipts (inline on transactions) paired with their booking, for project totals. */
    fun allReceipts(): List<de.ledgerline.app.core.finance.FinanceProjects.ReceiptRef> =
        transactions.value.filter { !it.trashed }.flatMap { tx ->
            de.ledgerline.app.data.FinanceRecordCodec.decodeReceipts(tx.raw)
                .map { de.ledgerline.app.core.finance.FinanceProjects.ReceiptRef(it, tx) }
        }

    fun projectTree() = FP.projectTree(projects.value)
    fun projectById(id: String) = projects.value.firstOrNull { it.id == id }
    fun projectRolledTotal(id: String) = FP.rolledTotal(projects.value, id, allReceipts())
    fun projectOwnTotal(p: de.ledgerline.app.domain.model.Project) = FP.ownTotal(p, allReceipts())

    fun newProject(parentId: String?, kind: String = "business") = de.ledgerline.app.domain.model.Project(
        id = de.ledgerline.app.core.Ids.newId(), parentId = parentId, kind = kind, created = java.time.LocalDate.now().toString(),
    )

    fun saveProject(p: de.ledgerline.app.domain.model.Project, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val res = repo.saveProjects { list ->
                if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else listOf(p) + list
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
