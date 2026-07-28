package de.ledgerline.app.core.geo

import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Coordinate display formats. Mirrors iOS `Geo/CoordinateFormat.swift`. */
enum class CoordinateFormat { DD, DDM, DMS, UTM, MGRS }

/**
 * Formats a lat/lng pair in decimal-degrees, degrees-decimal-minutes,
 * degrees-minutes-seconds, WGS-84 UTM or MGRS. Byte-exact port of iOS
 * `Geo/CoordinateFormatter.swift` (Snyder Transverse Mercator).
 */
object CoordinateFormatter {
    fun format(lat: Double, lng: Double, fmt: CoordinateFormat): String = when (fmt) {
        CoordinateFormat.DD -> String.format(Locale.US, "%.6f, %.6f", lat, lng)
        CoordinateFormat.DDM -> "${ddm(lat, true)}, ${ddm(lng, false)}"
        CoordinateFormat.DMS -> "${dms(lat, true)}, ${dms(lng, false)}"
        CoordinateFormat.UTM -> utmString(lat, lng)
        CoordinateFormat.MGRS -> mgrsString(lat, lng)
    }

    /** Swift `.rounded()` (half away from zero) — inputs here are non-negative. */
    private fun rounded(x: Double): Double = floor(x + 0.5)

    private fun ddm(v: Double, lat: Boolean): String {
        val hemi = if (lat) (if (v >= 0) "N" else "S") else (if (v >= 0) "E" else "W")
        // Round to 0.001′ first, then carry into degrees to prevent printing 60.000′.
        val totalMinutes = rounded(Math.abs(v) * 60 * 1000) / 1000
        val d = (totalMinutes / 60).toInt()
        val m = totalMinutes - d * 60
        return String.format(Locale.US, "%d°%06.3f′ %s", d, m, hemi)
    }

    private fun dms(v: Double, lat: Boolean): String {
        val hemi = if (lat) (if (v >= 0) "N" else "S") else (if (v >= 0) "E" else "W")
        // Round to 0.1″ first, then carry into minutes/degrees to prevent printing 60.0″ or 60′.
        val totalSeconds = rounded(Math.abs(v) * 3600 * 10) / 10
        val d = (totalSeconds / 3600).toInt()
        val rem = totalSeconds - d * 3600
        val m = (rem / 60).toInt()
        val s = rem - m * 60
        return String.format(Locale.US, "%d°%02d′%04.1f″ %s", d, m, s, hemi)
    }

    // MARK: - UTM / MGRS (WGS-84, Snyder Transverse Mercator)

    private data class UTMCoord(
        val zone: Int,
        val band: Char,
        val easting: Double,
        val northing: Double,
        val isNorth: Boolean,
    )

    private fun toUTM(lat: Double, lng: Double): UTMCoord {
        val a = 6_378_137.0
        val f = 1.0 / 298.257_223_563
        val k0 = 0.9996
        val e2 = f * (2 - f)
        val ep2 = e2 / (1 - e2)

        val zone = minOf(60, maxOf(1, ((lng + 180) / 6).toInt() + 1))
        val lon0 = zone * 6.0 - 183.0

        val latR = lat * Math.PI / 180
        val lonR = lng * Math.PI / 180
        val lon0R = lon0 * Math.PI / 180

        val sinLat = sin(latR)
        val cosLat = cos(latR)
        val tanLat = tan(latR)

        val n = a / sqrt(1 - e2 * sinLat * sinLat)
        val t = tanLat * tanLat
        val c = ep2 * cosLat * cosLat
        val aa = cosLat * (lonR - lon0R)
        val a2 = aa * aa; val a3 = a2 * aa; val a4 = a3 * aa; val a5 = a4 * aa; val a6 = a5 * aa

        val e4 = e2 * e2; val e6 = e4 * e2
        val m = a * (
            (1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * latR
                - (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * latR)
                + (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * latR)
                - (35 * e6 / 3072) * sin(6 * latR)
            )

        val easting = k0 * n * (
            aa
                + (1 - t + c) * a3 / 6
                + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a5 / 120
            ) + 500_000.0

        var northing = k0 * (
            m + n * tanLat * (
                a2 / 2
                    + (5 - t + 9 * c + 4 * c * c) * a4 / 24
                    + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a6 / 720
                )
            )
        val isNorth = lat >= 0
        if (!isNorth) northing += 10_000_000.0

        val bands = "CDEFGHJKLMNPQRSTUVWX"
        val bandIdx = minOf(bands.length - 1, maxOf(0, ((lat + 80) / 8).toInt()))
        val band = bands[bandIdx]

        return UTMCoord(zone = zone, band = band, easting = easting, northing = northing, isNorth = isNorth)
    }

    fun utmString(lat: Double, lng: Double): String {
        if (lat < -80 || lat > 84) return format(lat, lng, CoordinateFormat.DD)
        val u = toUTM(lat, lng)
        val e = rounded(u.easting).toLong()
        val n = rounded(u.northing).toLong()
        return "${u.zone}${u.band} $e $n"
    }

    fun mgrsString(lat: Double, lng: Double): String {
        if (lat < -80 || lat > 84) return format(lat, lng, CoordinateFormat.DD)
        val u = toUTM(lat, lng)

        // 100 km column letters: three groups of 8, cycled by zone mod 3.
        val colSets = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
        val colSet = colSets[(u.zone - 1) % 3]
        val colIdx = (u.easting / 100_000).toInt() - 1
        val col = colSet[maxOf(0, minOf(colSet.length - 1, colIdx))]

        // 100 km row letters: 20-letter alphabet, odd zones start at A, even at F.
        val rowLettersOdd = "ABCDEFGHJKLMNPQRSTUV"
        val rowLettersEven = "FGHJKLMNPQRSTUVABCDE"
        val rowAlpha = if (u.zone % 2 == 1) rowLettersOdd else rowLettersEven
        val rowIdx = (u.northing.rem(2_000_000.0) / 100_000).toInt()
        val row = rowAlpha[rowIdx % rowAlpha.length]

        val e = u.easting.rem(100_000.0).toInt()
        val n = u.northing.rem(100_000.0).toInt()
        return String.format(Locale.US, "%d%s %s%s %05d %05d", u.zone, u.band.toString(), col.toString(), row.toString(), e, n)
    }
}
