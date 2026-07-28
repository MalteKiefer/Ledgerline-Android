package de.ledgerline.app.core

import de.ledgerline.app.domain.model.ExploreStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Shares the last-loaded decrypted explore (tracks) store across the Explore ViewModels. */
@Singleton
class ExploreCache @Inject constructor() {
    private val _value = MutableStateFlow<ExploreStore?>(null)
    val value: StateFlow<ExploreStore?> = _value
    fun set(s: ExploreStore) { _value.value = s }
    fun clear() { _value.value = null }
}
