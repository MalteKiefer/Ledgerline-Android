package de.ledgerline.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The account's per-module entitlements (the "rights model"): `/me` → `user.modules` lists the
 * modules this account may use. The UI hides nav/tiles for modules not listed; the server also
 * enforces it (403 on a disabled module store). Until `/me` has been read — or when the server
 * doesn't send the field (older server) — [allowed] is null and EVERYTHING is treated as allowed
 * (fail-open on the client is safe: the server is the real gate, so we never wrongly hide before
 * we know, and never grant access the server would refuse).
 */
@Singleton
class ModuleAccess @Inject constructor() {

    private val _allowed = MutableStateFlow<Set<String>?>(null)
    /** The allowed module keys, or null = unknown/unrestricted. */
    val allowed: StateFlow<Set<String>?> = _allowed.asStateFlow()

    /** Adopt the module list from `/me` (`null` clears back to unrestricted). */
    fun set(modules: List<String>?) { _allowed.value = modules?.toSet() }

    /** True if [moduleKey] may be used (unknown list → allow). */
    fun allows(moduleKey: String): Boolean = _allowed.value?.let { moduleKey in it } ?: true

    fun clear() { _allowed.value = null }
}
