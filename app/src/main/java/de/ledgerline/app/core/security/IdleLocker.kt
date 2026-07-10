package de.ledgerline.app.core.security

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/** Tracks last-interaction time; decides when the VK must be wiped for inactivity. */
@Singleton
class IdleLocker @Inject constructor() {
    @Volatile var timeoutMs: Long = 5 * 60 * 1000  // configurable in settings later
    @Volatile private var lastActive = SystemClock.elapsedRealtime()

    fun touch() { lastActive = SystemClock.elapsedRealtime() }
    fun isExpired(): Boolean = SystemClock.elapsedRealtime() - lastActive >= timeoutMs
}
