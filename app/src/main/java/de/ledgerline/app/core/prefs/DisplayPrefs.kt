package de.ledgerline.app.core.prefs

import kotlin.math.roundToLong

/**
 * Global, non-secret DISPLAY preferences (measurement units + clock format) — the Android mirror of
 * the web `shared/prefs.js`. Presentation only: the underlying data stays zero-knowledge and
 * canonical storage is unchanged (metres, kg, °C, mg/dL); these choices just pick the display
 * unit/format. Synced with the server via `GET /me` (`me.preferences`) + `POST /api/v1/preferences`.
 */
data class DisplayPrefs(
    val distance: String = "km",       // km | mi
    val elevation: String = "m",       // m | ft
    val weight: String = "kg",         // kg | lb
    val temp: String = "c",            // c | f
    val glucose: String = "mgdl",      // mgdl | mmoll
    val timeFormat: String = "24h",    // 24h | 12h
    val dateFormat: String = "system", // system | dmy | dmy_dot | mdy | ymd
    val timezone: String = "",         // IANA override (e.g. Europe/Berlin); "" = follow system
) {
    val imperialDistance: Boolean get() = distance == "mi"
    val feetElevation: Boolean get() = elevation == "ft"
    val is12h: Boolean get() = timeFormat == "12h"

    companion object {
        const val METERS_PER_MILE = 1609.344
        const val FEET_PER_METER = 3.280839895

        /** Server wire keys → the six fields; unknown keys ignored, missing keys keep defaults. */
        fun fromMap(map: Map<String, String>): DisplayPrefs = DisplayPrefs(
            distance = map["distance"] ?: "km",
            elevation = map["elevation"] ?: "m",
            weight = map["weight"] ?: "kg",
            temp = map["temp"] ?: "c",
            glucose = map["glucose"] ?: "mgdl",
            timeFormat = map["time_format"] ?: "24h",
            dateFormat = map["date_format"] ?: "system",
            timezone = map["timezone"] ?: "",
        )
    }

    /** Wire map for `POST /preferences` (snake_case `time_format`, others verbatim). */
    fun toMap(): Map<String, String> = mapOf(
        "distance" to distance, "elevation" to elevation, "weight" to weight,
        "temp" to temp, "glucose" to glucose, "time_format" to timeFormat,
        "date_format" to dateFormat, "timezone" to timezone,
    )

    /** Distance in the user's unit as a bare number (chart axes / stats). Canonical = metres. */
    fun distanceValue(meters: Double, digits: Int = 2): Double {
        val f = if (imperialDistance) meters / METERS_PER_MILE else meters / 1000.0
        val p = Math.pow(10.0, digits.toDouble())
        return (f * p).roundToLong() / p
    }

    fun distanceUnitLabel(): String = if (imperialDistance) "mi" else "km"

    /** Elevation in the user's unit as a bare number. Canonical = metres. */
    fun elevationValue(meters: Double, digits: Int = 0): Double {
        val f = if (feetElevation) meters * FEET_PER_METER else meters
        val p = Math.pow(10.0, digits.toDouble())
        return (f * p).roundToLong() / p
    }

    fun elevationUnitLabel(): String = if (feetElevation) "ft" else "m"
}
