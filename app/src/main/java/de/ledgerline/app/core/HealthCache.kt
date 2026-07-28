package de.ledgerline.app.core

import de.ledgerline.app.domain.model.HealthStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Shares the last-loaded decrypted health store across the Health ViewModels. */
@Singleton
class HealthCache @Inject constructor() {
    private val _value = MutableStateFlow<HealthStore?>(null)
    val value: StateFlow<HealthStore?> = _value
    fun set(s: HealthStore) { _value.value = s }
    fun clear() { _value.value = null }
}
