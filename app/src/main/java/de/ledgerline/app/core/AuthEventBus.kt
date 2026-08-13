package de.ledgerline.app.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the process-global [AuthNotifier] callback into an observable flow and
 * registers itself as the notifier on construction. Any authenticated 401 (revoked
 * token) fired by the NetworkFactory interceptor is re-emitted here so the app can
 * force a full logout + wipe.
 */
@Singleton
class AuthEventBus @Inject constructor() {
    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val unauthorized: SharedFlow<Unit> = _unauthorized

    /** Remote kill switch: the owner flagged this device to wipe (via `GET /me` → `wipe:true`). */
    private val _wipe = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val wipe: SharedFlow<Unit> = _wipe

    /**
     * Workspace force-2FA policy tripped: an authenticated request got 403
     * `two_factor_required`. The app must gate the user into 2FA enrollment.
     */
    private val _twoFactorRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val twoFactorRequired: SharedFlow<Unit> = _twoFactorRequired

    fun emitWipe() { _wipe.tryEmit(Unit) }

    init {
        AuthNotifier.onUnauthorized = { _unauthorized.tryEmit(Unit) }
        AuthNotifier.onTwoFactorRequired = { _twoFactorRequired.tryEmit(Unit) }
    }
}
