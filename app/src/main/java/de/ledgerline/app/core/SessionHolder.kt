package de.ledgerline.app.core

import de.ledgerline.app.domain.model.Session
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the paired Session (baseUrl/token/SPKI pin) in memory after a successful
 * unlock, so authenticated API calls don't re-prompt biometric on every request.
 * Cleared alongside the Vault Key (background/idle lock).
 */
@Singleton
class SessionHolder @Inject constructor() {
    @Volatile private var session: Session? = null
    fun set(s: Session) { session = s }
    fun get(): Session? = session
    fun clear() { session = null }
}
