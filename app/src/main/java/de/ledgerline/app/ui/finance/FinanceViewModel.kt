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

    fun invoiceById(id: String): Invoice? = invoices.value.firstOrNull { it.id == id }

    fun totals(inv: Invoice) = InvoiceMath.totals(inv)

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
