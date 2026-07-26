package de.ledgerline.app.core.security

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/** Tracks last-interaction time; decides when the VK must be wiped for inactivity. */
@Singleton
class IdleLocker @Inject constructor() {
    @Volatile var timeoutMs: Long = DEFAULT_TIMEOUT_MS
        private set

    @Volatile private var lastActive = SystemClock.elapsedRealtime()

    /** Set the idle timeout; non-positive values fall back to the default. */
    fun setTimeoutMs(ms: Long) { timeoutMs = if (ms > 0) ms else DEFAULT_TIMEOUT_MS }

    fun touch() { lastActive = SystemClock.elapsedRealtime() }
    fun isExpired(): Boolean = SystemClock.elapsedRealtime() - lastActive >= timeoutMs

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
