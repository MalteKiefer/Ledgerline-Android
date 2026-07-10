package de.ledgerline.app.core

import de.ledgerline.app.domain.model.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Shares the last-loaded decrypted workspace across the four tab ViewModels. */
@Singleton
class WorkspaceCache @Inject constructor() {
    private val _value = MutableStateFlow<Workspace?>(null)
    val value: StateFlow<Workspace?> = _value
    fun set(w: Workspace) { _value.value = w }
    fun clear() { _value.value = null }
}
