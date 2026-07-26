package de.ledgerline.app.core

import de.ledgerline.app.domain.model.SecretsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Shares the last-loaded decrypted secrets store across the password-manager ViewModels. */
@Singleton
class PasswordsCache @Inject constructor() {
    private val _value = MutableStateFlow<SecretsStore?>(null)
    val value: StateFlow<SecretsStore?> = _value
    fun set(s: SecretsStore) { _value.value = s }
    fun clear() { _value.value = null }
}
