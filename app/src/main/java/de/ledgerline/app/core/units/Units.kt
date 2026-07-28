package de.ledgerline.app.core.units

import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/** Metric vs imperial unit system. Mirrors iOS `Units/UnitSystem.swift`. */
enum class UnitSystem { METRIC, IMPERIAL }

/**
 * Formats distances, elevations, pace, speed and durations for the chosen
 * `UnitSystem`. Pure and framework-free. Number formatting mirrors iOS
 * `Units/MeasureFormatter.swift` (C `printf`-style, half-up rounding, `.` separator
 * via `Locale.US`).
 */
object MeasureFormatter {
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.280839895

    /** e.g. "12.34 km" / "7.67 mi". */
    fun distance(meters: Double, unit: UnitSystem): String = when (unit) {
        UnitSystem.METRIC -> String.format(Locale.US, "%.2f km", meters / 1000)
        UnitSystem.IMPERIAL -> String.format(Locale.US, "%.2f mi", meters / METERS_PER_MILE)
    }

    /** e.g. "820 m" / "2690 ft". */
    fun elevation(meters: Double, unit: UnitSystem): String = when (unit) {
        UnitSystem.METRIC -> String.format(Locale.US, "%.0f m", meters)
        UnitSystem.IMPERIAL -> String.format(Locale.US, "%.0f ft", meters * FEET_PER_METER)
    }

    /** e.g. "18.0 km/h" / "11.2 mph". */
    fun speed(mps: Double, unit: UnitSystem): String {
        val kmh = mps * 3.6
        return when (unit) {
            UnitSystem.METRIC -> String.format(Locale.US, "%.1f km/h", kmh)
            UnitSystem.IMPERIAL -> String.format(Locale.US, "%.1f mph", kmh / METERS_PER_MILE * 1000)
        }
    }

    /** e.g. "5'33\"/km" / "8'56\"/mi"; "--" when speed is non-positive. */
    fun pace(mps: Double, unit: UnitSystem): String {
        if (mps <= 0) return "--"
        val secondsPerUnit: Double
        val suffix: String
        when (unit) {
            UnitSystem.METRIC -> { secondsPerUnit = 1000 / mps; suffix = "/km" }
            UnitSystem.IMPERIAL -> { secondsPerUnit = METERS_PER_MILE / mps; suffix = "/mi" }
        }
        val m = secondsPerUnit.toInt() / 60
        val s = secondsPerUnit.toInt() % 60
        return String.format(Locale.US, "%d'%02d\"%s", m, s, suffix)
    }

    /** Elapsed time. "H:MM:SS" when ≥ 1 h, else "M:SS" (seconds zero-padded to 2). */
    fun duration(seconds: Double): String {
        val total = max(0L, floor(seconds).toLong())
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
