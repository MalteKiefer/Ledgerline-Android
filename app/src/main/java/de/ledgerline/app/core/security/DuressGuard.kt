package de.ledgerline.app.core.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Duress auto-wipe policy. After [effectiveThreshold] consecutive wrong vault
 * passphrases, everything held locally is destroyed (see the wipe path in
 * `UnlockViewModel` → `AuthEventBus.emitWipe` → `ForceLogout`). The feature CANNOT
 * be disabled — the threshold is always one of [options] (default
 * [defaultThreshold]); a stored out-of-range value falls back to the default.
 *
 * Only genuine wrong-passphrase attempts count — never biometric mismatches
 * (an attacker without the owner's biometric is simply dropped to the passphrase),
 * never recovery-code failures, never transport errors. Each counted attempt already
 * costs a full Argon2id derivation and is rate-limited by [UnlockThrottle], so an
 * attacker cannot cheaply drive the counter to the threshold.
 */
object WipePolicy {
    /** Selectable thresholds shown in Settings. No "off" — always active. */
    val options = listOf(3, 5, 10, 15, 20)
    const val defaultThreshold = 10

    /** Any out-of-range/stored value falls back to the default (feature never off). */
    fun effectiveThreshold(stored: Int): Int = if (stored in options) stored else defaultThreshold

    fun shouldWipe(failures: Int, threshold: Int): Boolean = failures >= effectiveThreshold(threshold)

    /** Attempts left before a wipe. */
    fun remaining(failures: Int, threshold: Int): Int = maxOf(0, effectiveThreshold(threshold) - failures)
}

/**
 * The consecutive wrong-passphrase counter, sealed with a hardware-backed
 * AES-256-GCM key ([KeystoreSealer], `requireAuth=false`) and persisted to disk so
 * force-quitting the app cannot reset it toward the duress threshold.
 */
@Singleton
class DuressGuard @Inject constructor(@ApplicationContext context: Context) {
    private val sealer = KeystoreSealer(alias = "ledgerline_failctr_key_v1", requireAuth = false)
    private val file = File(context.filesDir, "failed_attempts.bin")

    @Synchronized
    fun count(): Int = try {
        if (!file.exists()) 0 else String(sealer.open(file.readBytes()), Charsets.UTF_8).toIntOrNull() ?: 0
    } catch (_: Exception) {
        0
    }

    @Synchronized
    fun increment(): Int {
        val next = count() + 1
        persist(next)
        return next
    }

    @Synchronized
    fun reset() {
        runCatching { file.delete() }
    }

    private fun persist(n: Int) {
        try {
            file.writeBytes(sealer.seal(n.toString().toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
            // Best-effort; a failed persist just means the counter under-counts, never over-wipes.
        }
    }
}
