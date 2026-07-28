package de.ledgerline.app.core

import de.ledgerline.app.domain.model.CompanyProfile
import de.ledgerline.app.domain.model.FinanceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Shares the last-loaded decrypted finance store + company profile across the Finance ViewModels. */
@Singleton
class FinanceCache @Inject constructor() {
    private val _value = MutableStateFlow<FinanceStore?>(null)
    val value: StateFlow<FinanceStore?> = _value
    private val _company = MutableStateFlow<CompanyProfile?>(null)
    val company: StateFlow<CompanyProfile?> = _company
    fun set(s: FinanceStore) { _value.value = s }
    fun setCompany(c: CompanyProfile) { _company.value = c }
    fun clear() { _value.value = null; _company.value = null }
}
