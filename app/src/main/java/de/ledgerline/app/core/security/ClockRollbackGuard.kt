package de.ledgerline.app.core.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forward-only wall-clock high-water mark, to detect a **clock rollback** used to
 * extend a TTL-limited trust (the remembered-vault biometric unlock keys off a
 * wall-clock expiry — see [RememberedVaultStore]). Mirrors the iOS `ClockRollbackGuard`.
 *
 * [observe] records the highest wall-clock time ever seen (sealed to disk with a
 * hardware-backed key so it is not trivially reset). If a later reading is more than
 * [TOLERANCE_MS] behind the high-water, the clock moved backwards → callers must
 * fail closed (require the passphrase again) rather than honour the stale expiry.
 * A small tolerance absorbs benign NTP/timezone corrections; a large legitimate
 * change only costs one passphrase re-entry.
 */
@Singleton
class ClockRollbackGuard @Inject constructor(@ApplicationContext context: Context) {
    private val sealer = KeystoreSealer(alias = "ledgerline_clockhw_key_v1", requireAuth = false)
    private val file = File(context.filesDir, "clock_highwater.bin")
    private var highWater: Long = load()

    /**
     * Record [now] and report whether a rollback was detected. Returns true when the
     * clock has moved backwards beyond [TOLERANCE_MS] of the high-water (fail closed);
     * otherwise advances the high-water forward and returns false.
     */
    @Synchronized
    fun observe(now: Long): Boolean {
        if (isRollback(now, highWater, TOLERANCE_MS)) return true
        if (now > highWater) {
            highWater = now
            persist(now)
        }
        return false
    }

    private fun load(): Long = try {
        if (!file.exists()) 0L else String(sealer.open(file.readBytes()), Charsets.UTF_8).toLongOrNull() ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun persist(v: Long) {
        try {
            file.writeBytes(sealer.seal(v.toString().toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
            // Best-effort; a failed persist only means the guard under-tracks, never over-trusts.
        }
    }

    /** Reset the high-water (called by a full wipe so a re-paired device starts clean). */
    @Synchronized
    fun reset() {
        highWater = 0L
        runCatching { file.delete() }
    }

    companion object {
        const val TOLERANCE_MS = 5 * 60_000L // 5 min — absorbs benign NTP/timezone corrections

        /** Pure decision (unit-testable): is [now] a rollback vs the [highWater]? */
        fun isRollback(now: Long, highWater: Long, toleranceMs: Long = TOLERANCE_MS): Boolean =
            now < highWater - toleranceMs
    }
}
