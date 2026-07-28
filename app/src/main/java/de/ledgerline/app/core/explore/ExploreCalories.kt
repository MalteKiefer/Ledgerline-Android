package de.ledgerline.app.core.explore

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Rough energy-expenditure estimate for a tour — a faithful port of the web
 * `shared/explore-calories.js` (MET-by-speed + an explicit vertical-work term). Only shown when
 * the user's weight is on file. Pure + unit-testable.
 */
object ExploreCalories {

    /**
     * Estimated ACTIVE kcal, or null when it can't be computed. [durationS] = real moving time; when
     * 0 (e.g. a planned route) it's estimated from distance+ascent via Naismith's rule. [sex] 'f'
     * gets a rough lean-mass adjustment.
     */
    fun estimate(distanceM: Double, durationS: Double, ascentM: Double, weightKg: Double, sex: String?): Long? {
        if (weightKg <= 0 || distanceM <= 0) return null
        val distKm = distanceM / 1000.0
        val ascent = max(0.0, ascentM)

        val hours = if (durationS > 0) durationS / 3600.0 else distKm / 4.5 + ascent / 600.0
        if (hours <= 0) return null

        val speedKmh = distKm / hours
        var met = when {
            speedKmh < 3.2 -> 2.5
            speedKmh < 4.8 -> 3.5
            speedKmh < 6.4 -> 5.0
            else -> 6.5
        }
        if (ascent / distKm > 40) met += 1.5

        var kcal = max(0.0, met - 1) * weightKg * hours
        kcal += weightKg * ascent * 0.0098 // extra vertical work (mgh at ~24% efficiency)
        if (sex == "f") kcal *= 0.92

        return kcal.roundToLong()
    }
}
