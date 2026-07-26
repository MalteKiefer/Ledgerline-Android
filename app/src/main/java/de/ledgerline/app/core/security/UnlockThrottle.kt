package de.ledgerline.app.core.security

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rate-limits repeated unlock failures with escalating, MONOTONIC backoff. The
 * response to repeated failure is a growing DELAY — never a wipe — so an attacker
 * holding the device cannot drive a destructive action here, and each attempt
 * already costs a full Argon2id derivation. Per-device and in-memory (resets on
 * process death, like the iOS `UnlockThrottle`); the destructive duress counter is
 * separate and persisted (see [DuressGuard]).
 *
 * After [freeAttempts] failures the lockout grows 2s, 4s, 8s … capped at
 * [maxDelayMs]. Time is measured with [SystemClock.elapsedRealtime] so a wall-clock
 * change cannot shorten a lockout.
 */
@Singleton
class UnlockThrottle @Inject constructor() {
    private val freeAttempts = 3
    private val maxDelayMs = 300_000L

    private var failures = 0
    private var lockedUntil = 0L // elapsedRealtime millis

    @Synchronized
    fun recordSuccess() {
        failures = 0
        lockedUntil = 0L
    }

    @Synchronized
    fun recordFailure(now: Long = SystemClock.elapsedRealtime()) {
        failures++
        if (failures <= freeAttempts) return
        val exponent = minOf(failures - freeAttempts, 20) // clamp so the shift never overflows
        val delay = minOf((1L shl exponent) * 1000L, maxDelayMs) // 2s, 4s, 8s … cap
        lockedUntil = now + delay
    }

    /** Remaining lockout in millis, or 0 if an attempt is allowed now. */
    @Synchronized
    fun remainingLockMs(now: Long = SystemClock.elapsedRealtime()): Long =
        if (lockedUntil > now) lockedUntil - now else 0L
}
