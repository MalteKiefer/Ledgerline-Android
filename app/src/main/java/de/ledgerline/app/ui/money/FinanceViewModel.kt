package de.ledgerline.app.ui.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.data.finance.FinanceRepository
import de.ledgerline.app.domain.model.finance.BankTransaction
import de.ledgerline.app.domain.model.finance.CompanyProfile
import de.ledgerline.app.domain.model.finance.FinanceData
import de.ledgerline.app.domain.model.finance.FinanceReports
import de.ledgerline.app.domain.model.finance.Invoice
import de.ledgerline.app.domain.model.finance.PaymentMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

/**
 * Shell-scoped state for the finance app: exposes the [FinanceRepository] snapshot + server-computed
 * reports, and thin mutation wrappers the screens call. Loaded once when the shell enters (cache-first
 * paints instantly), refreshable via pull. All amounts are plaintext strings from the API.
 */
@HiltViewModel
class FinanceViewModel @Inject constructor(
    private val repo: FinanceRepository,
) : ViewModel() {
    val data: StateFlow<FinanceData?> = repo.data

    private val _reports = MutableStateFlow<FinanceReports?>(null)
    val reports: StateFlow<FinanceReports?> = _reports.asStateFlow()

    private val _year = MutableStateFlow(java.time.Year.now().value)
    val year: StateFlow<Int> = _year.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            repo.load()
            _reports.value = repo.reports(_year.value)
            _loading.value = false
            _refreshing.value = false
        }
    }

    fun pullRefresh() { _refreshing.value = true; refresh() }

    fun setYear(y: Int) {
        _year.value = y
        viewModelScope.launch { _reports.value = repo.reports(y) }
    }

    // ---- lookups ----
    fun invoice(id: Int) = data.value?.invoices?.firstOrNull { it.id == id }
    fun transaction(id: Int) = data.value?.transactions?.firstOrNull { it.id == id }
    fun partner(id: Int) = data.value?.partners?.firstOrNull { it.id == id }
    fun paymentMethod(id: Int) = data.value?.paymentMethods?.firstOrNull { it.id == id }
    fun project(id: Int) = data.value?.projects?.firstOrNull { it.id == id }
    fun partnerName(id: Int?): String? = id?.let { partner(it)?.name }

    // ---- mutations (fire-and-forget with a completion callback) ----
    private fun <T> run(block: suspend () -> Outcome<T>, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = block() is Outcome.Ok
            if (ok) _reports.value = repo.reports(_year.value) // keep analytics in step
            done(ok)
        }
    }

    fun saveInvoice(id: Int?, body: JsonObject, done: (Boolean) -> Unit) =
        run({ if (id == null) repo.createInvoice(body) else repo.updateInvoice(id, body) }, done)
    fun finalizeInvoice(id: Int, done: (Boolean) -> Unit) = run({ repo.finalizeInvoice(id) }, done)
    suspend fun invoicePdf(id: Int): ByteArray? = repo.invoicePdf(id)
    fun deleteInvoice(id: Int, done: (Boolean) -> Unit) = run({ repo.deleteInvoice(id) }, done)

    fun saveTransaction(id: Int?, body: JsonObject, done: (Boolean) -> Unit) =
        run({ if (id == null) repo.createTransaction(body) else repo.updateTransaction(id, body) }, done)
    fun deleteTransaction(id: Int, done: (Boolean) -> Unit) = run({ repo.deleteTransaction(id) }, done)
    fun attachReceipt(txId: Int, bytes: ByteArray, name: String, mime: String, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(repo.attachReceipt(txId, bytes, name, mime) is Outcome.Ok) }
    fun deleteReceipt(txId: Int, receiptId: String, done: (Boolean) -> Unit) =
        viewModelScope.launch { done(repo.deleteReceipt(txId, receiptId) is Outcome.Ok) }
    suspend fun receiptBytes(txId: Int, receiptId: String) = repo.receiptBytes(txId, receiptId)
    suspend fun bulkImport(paymentMethodId: Int, lines: List<kotlinx.serialization.json.JsonObject>) = repo.bulkImport(paymentMethodId, lines)

    fun savePartner(id: Int?, body: JsonObject, done: (Boolean) -> Unit) =
        run({ if (id == null) repo.createPartner(body) else repo.updatePartner(id, body) }, done)
    fun deletePartner(id: Int, done: (Boolean) -> Unit) = run({ repo.deletePartner(id) }, done)

    fun savePaymentMethod(id: Int?, body: JsonObject, done: (Boolean) -> Unit) =
        run({ if (id == null) repo.createPaymentMethod(body) else repo.updatePaymentMethod(id, body) }, done)
    fun deletePaymentMethod(id: Int, done: (Boolean) -> Unit) = run({ repo.deletePaymentMethod(id) }, done)

    fun saveProject(id: Int?, body: JsonObject, done: (Boolean) -> Unit) =
        run({ if (id == null) repo.createProject(body) else repo.updateProject(id, body) }, done)
    fun deleteProject(id: Int, done: (Boolean) -> Unit) = run({ repo.deleteProject(id) }, done)

    fun saveCategory(id: Int?, body: JsonObject, done: (Boolean) -> Unit) =
        run({ if (id == null) repo.createCategory(body) else repo.updateCategory(id, body) }, done)

    suspend fun loadDuplicates() = repo.duplicates()
    suspend fun loadSuggestions() = repo.categorySuggestions()

    /** Apply a suggested category to a transaction (sets its vat_cat/category via update). */
    fun applySuggestion(txId: Int, category: String, done: (Boolean) -> Unit) {
        val tx = transaction(txId) ?: return done(false)
        val body = kotlinx.serialization.json.buildJsonObject {
            put("version", kotlinx.serialization.json.JsonPrimitive(tx.version))
            put("vat_cat", kotlinx.serialization.json.JsonPrimitive(category))
        }
        run({ repo.updateTransaction(txId, body) }, done)
    }

    suspend fun loadCompany(): CompanyProfile? = repo.company()
    fun saveCompany(profile: CompanyProfile, done: (Boolean) -> Unit) {
        viewModelScope.launch { done(repo.updateCompany(profile) != null) }
    }

    companion object {
        private val euro: NumberFormat = NumberFormat.getCurrencyInstance(Locale.GERMANY)
        /** Format a plaintext money string (or number) as currency; blank/invalid → "—". */
        fun money(v: String?, currency: String = "EUR"): String {
            val d = v?.replace(',', '.')?.toDoubleOrNull() ?: return "—"
            return runCatching { euro.format(d) }.getOrDefault("$d $currency")
        }
        fun money(v: Double): String = runCatching { euro.format(v) }.getOrDefault(v.toString())
    }
}
