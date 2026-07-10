package de.ledgerline.app.core.security

import javax.inject.Inject
import javax.inject.Singleton

/** Suppresses exactly one onStop-triggered auto-lock — used when the app launches
 *  its own system picker (SAF) or credential prompt, which briefly backgrounds it. */
@Singleton
class LockGuard @Inject constructor() {
    @Volatile private var skipNextStop = false
    fun armSkipOnce() { skipNextStop = true }
    /** Returns true (and consumes) if the next stop should be skipped. */
    fun consumeSkip(): Boolean { val s = skipNextStop; skipNextStop = false; return s }
    fun clear() { skipNextStop = false }
}
