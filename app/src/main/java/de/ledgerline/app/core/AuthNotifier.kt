package de.ledgerline.app.core

/**
 * Bridge so the (object) NetworkFactory interceptor can notify the app of an
 * authenticated 401 (revoked token) without a Hilt dependency. Set once at startup
 * by [AuthEventBus].
 */
object AuthNotifier {
    @Volatile
    var onUnauthorized: (() -> Unit)? = null

    /**
     * Workspace force-2FA policy: an authenticated request returned 403
     * `{status:"two_factor_required"}`. The user must enroll a second factor before the
     * app can be used. Set once at startup by [AuthEventBus].
     */
    @Volatile
    var onTwoFactorRequired: (() -> Unit)? = null
}
