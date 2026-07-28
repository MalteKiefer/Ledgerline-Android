package de.ledgerline.app.core.health

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure health-metric logic — a faithful port of the web `resources/js/shared/health-metrics.js`
 * (no side effects, unit-testable). Canonical storage units never change at rest (weight = kg,
 * temp = °C, glucose = mg/dL, pulse = bpm, spo2 = %, bp = mmHg); display conversions apply the
 * user's [HealthUnits]. BMI/age are derived, never stored.
 */
object HealthMetrics {

    /** One metric in the ordered registry (drives nav chips, labels, units, tints, chart config). */
    data class Metric(
        val key: String,
        val unit: String,
        /** Category-tint hex, byte-identical to the web `tintHex` map. */
        val tint: Long,
        val dual: Boolean,
    )

    /** Ordered registry: weight, bp (dual), pulse, spo2, temp, glucose — same order as the web. */
    val METRICS: List<Metric> = listOf(
        Metric("weight", "kg", 0xFF0EA5E9, dual = false),  // sky
        Metric("bp", "mmHg", 0xFFF43F5E, dual = true),     // rose
        Metric("pulse", "bpm", 0xFFEC4899, dual = false),  // pink
        Metric("spo2", "%", 0xFF3B82F6, dual = false),     // blue
        Metric("temp", "°C", 0xFFF59E0B, dual = false),    // amber
        Metric("glucose", "mg/dL", 0xFF22C55E, dual = false), // green
    )

    private val byKey = METRICS.associateBy { it.key }

    fun metric(key: String): Metric? = byKey[key]

    // ---- Age / BMI ---------------------------------------------------------

    /** Whole years from a `YYYY-MM-DD` birthdate as of [now]; null when empty/invalid. */
    fun computeAge(birthdate: String?, now: LocalDate): Int? {
        if (birthdate.isNullOrBlank()) return null
        val bd = try { LocalDate.parse(birthdate) } catch (_: Exception) { return null }
        var age = now.year - bd.year
        if (now.monthValue < bd.monthValue || (now.monthValue == bd.monthValue && now.dayOfMonth < bd.dayOfMonth)) {
            age -= 1
        }
        return age
    }

    /** BMI = kg / m², rounded to 1 dp; null on missing/non-positive inputs. */
    fun computeBmi(weightKg: Double?, heightCm: Double?): Double? {
        if (weightKg == null || heightCm == null || weightKg <= 0 || heightCm <= 0) return null
        val m = heightCm / 100.0
        return round1(weightKg / (m * m))
    }

    // ---- Unit conversions (byte-exact to the web) --------------------------

    fun kgToLb(kg: Double): Double = round1(kg * 2.20462)
    fun lbToKg(lb: Double): Double = round1(lb / 2.20462)
    fun cToF(c: Double): Double = round1(c * 9 / 5 + 32)
    fun fToC(f: Double): Double = round1((f - 32) * 5 / 9)
    fun mgdlToMmoll(v: Double): Double = round1(v / 18.0182)
    fun mmollToMgdl(v: Double): Double = (v * 18.0182).roundToInt().toDouble()

    // ---- Classification (clinical traffic-light) ---------------------------

    enum class Status { OK, AMBER, RED }

    /**
     * Clinical status per canonical-unit thresholds (identical to web `classify`):
     * spo2 <92 red / <95 amber; bp worst-of(sys,dia) ≥140|≥90 red / ≥121|≥81 amber; pulse 60–100 ok;
     * temp ≥39 red / ≥38 amber; weight & glucose always ok.
     */
    fun classify(key: String, v: Double, v2: Double?): Status = when (key) {
        "spo2" -> when {
            v < 92 -> Status.RED
            v < 95 -> Status.AMBER
            else -> Status.OK
        }
        "bp" -> {
            val sys = v
            val dia = v2 ?: 0.0
            when {
                sys >= 140 || dia >= 90 -> Status.RED
                sys >= 121 || dia >= 81 -> Status.AMBER
                else -> Status.OK
            }
        }
        "pulse" -> if (v in 60.0..100.0) Status.OK else Status.AMBER
        "temp" -> when {
            v >= 39 -> Status.RED
            v >= 38 -> Status.AMBER
            else -> Status.OK
        }
        else -> Status.OK // weight, glucose, unknown
    }

    // ---- helpers -----------------------------------------------------------

    /** Round to 1 decimal place, matching the web `Math.round(x*10)/10`. */
    fun round1(x: Double): Double = (x * 10).roundToInt() / 10.0

    fun clamp(x: Double, lo: Double, hi: Double): Double = max(lo, min(hi, x))
}
