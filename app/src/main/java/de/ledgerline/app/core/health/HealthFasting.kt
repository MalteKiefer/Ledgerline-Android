package de.ledgerline.app.core.health

import de.ledgerline.app.domain.model.HealthFast
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Pure intermittent-fasting logic — a faithful port of `resources/js/shared/health-fasting.js`.
 * A fast is `{ id, start, end|null, targetHours, note }`; the single active fast is the one with
 * `end == null` (invariant enforced by [normalizeFasts] + the store's optimistic version).
 */
object HealthFasting {

    /** Common protocols: `targetHours` is the FASTING window (the X in X:Y, Y = 24 − X eating). */
    data class Template(val key: String, val targetHours: Int)

    val TEMPLATES: List<Template> = listOf(
        Template("12:12", 12),
        Template("14:10", 14),
        Template("16:8", 16),
        Template("18:6", 18),
        Template("20:4", 20),
    )

    data class Progress(val elapsed: Long, val target: Long, val fraction: Double, val reached: Boolean)

    /** The single active fast (`end == null`), or null. */
    fun activeFast(fasts: List<HealthFast>): HealthFast? = fasts.firstOrNull { it.end.isNullOrEmpty() }

    /** Parse an ISO-8601 instant to epoch millis, or null. */
    private fun parseMs(iso: String?): Long? {
        if (iso.isNullOrEmpty()) return null
        return try { Instant.parse(iso).toEpochMilli() } catch (_: Exception) { null }
    }

    /**
     * Enforce the single-active-fast invariant. A concurrent start on two clients can leave two
     * records with `end == null` after the store rebase-merge; deterministically keep the
     * earliest-started one active (tie-break by id so every client converges) and void the rest by
     * closing them at their own start (zero-length, not deleted). Returns the corrected list and
     * whether anything changed. Pure (unlike the web's in-place mutation — the repository re-saves).
     */
    fun normalizeFasts(fasts: List<HealthFast>): Pair<List<HealthFast>, Boolean> {
        val activeIdx = fasts.withIndex().filter { it.value.end.isNullOrEmpty() }
        if (activeIdx.size <= 1) return fasts to false
        // Sort the active ones by (start, id); everyone but the first is voided.
        val sorted = activeIdx.sortedWith(
            compareBy<IndexedValue<HealthFast>>({ parseMs(it.value.start) ?: 0L }).thenBy { it.value.id },
        )
        val voidIds = sorted.drop(1).map { it.value.id }.toSet()
        if (voidIds.isEmpty()) return fasts to false
        val out = fasts.map { f -> if (f.id in voidIds) f.copy(end = f.start) else f }
        return out to true
    }

    /** Elapsed seconds of a fast (to its end, or to [nowMs] if still running). */
    fun elapsedSeconds(fast: HealthFast, nowMs: Long): Long {
        val start = parseMs(fast.start) ?: return 0
        val end = if (!fast.end.isNullOrEmpty()) parseMs(fast.end) ?: return 0 else nowMs
        val s = (end - start) / 1000
        return if (s > 0) s else 0
    }

    /** Target duration in seconds (targetHours × 3600), or 0 if unset. */
    fun targetSeconds(fast: HealthFast): Long {
        val h = fast.targetHours
        return if (h != null && h > 0) (h * 3600.0).roundToLong() else 0
    }

    fun progress(fast: HealthFast, nowMs: Long): Progress {
        val target = targetSeconds(fast)
        val elapsed = elapsedSeconds(fast, nowMs)
        val fraction = if (target > 0) max(0.0, elapsed.toDouble() / target) else 0.0
        return Progress(elapsed, target, fraction, target > 0 && elapsed >= target)
    }

    /** "Xh MMm" (e.g. 5040 → "1h 24m"). */
    fun formatDuration(seconds: Long): String {
        val s = max(0L, seconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        return "${h}h ${m.toString().padStart(2, '0')}m"
    }

    /** "HH:MM:SS" for the live fasting timer (e.g. 5040 → "01:24:00"). */
    fun formatDurationHMS(seconds: Long): String {
        val s = max(0L, seconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return listOf(h, m, sec).joinToString(":") { it.toString().padStart(2, '0') }
    }

    /** The X:Y label for a fasting window given its fasting hours. */
    fun templateLabel(targetHours: Int?): String {
        val h = targetHours ?: return ""
        if (h <= 0) return ""
        TEMPLATES.firstOrNull { it.targetHours == h }?.let { return it.key }
        val eat = max(0, 24 - h)
        return "$h:$eat"
    }

    /** Percent toward target, 0–100. */
    fun pct(fast: HealthFast, nowMs: Long): Int = (progress(fast, nowMs).fraction * 100).roundToInt().coerceIn(0, 100)

    /** Validate a fast before saving (start required; end after start; 0 < target ≤ 48). */
    fun isValid(start: String?, end: String?, targetHours: Int?): Boolean {
        val s = parseMs(start) ?: return false
        if (!end.isNullOrEmpty()) {
            val e = parseMs(end) ?: return false
            if (e <= s) return false
        }
        val h = targetHours ?: return false
        return h in 1..48
    }
}
