package de.ledgerline.app.core

/**
 * Bridge so the (object) NetworkFactory interceptor can notify the app of an
 * authenticated 401 (revoked token) without a Hilt dependency. Set once at startup
 * by [AuthEventBus].
 */
object AuthNotifier {
    @Volatile
    var onUnauthorized: (() -> Unit)? = null
}
