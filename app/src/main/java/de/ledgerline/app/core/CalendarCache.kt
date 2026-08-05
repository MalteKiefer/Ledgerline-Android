package de.ledgerline.app.core

import de.ledgerline.app.domain.model.CalendarStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Shares the last-loaded decrypted calendar store across the Calendar ViewModels. */
@Singleton
class CalendarCache @Inject constructor() {
    private val _value = MutableStateFlow<CalendarStore?>(null)
    val value: StateFlow<CalendarStore?> = _value
    fun set(s: CalendarStore) { _value.value = s }
    fun clear() { _value.value = null }
}
