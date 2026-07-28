package de.ledgerline.app.core.explore

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Byte-exact reproduction of web `resources/js/shared/track-parse.js`
 * (`computeStats` + `smoothedAscentDescent` + `haversineM`). Any drift from the
 * web output is a cross-client bug.
 *
 * NOTE: web computes ascent/descent via the ±5 m hysteresis dead-band
 * `smoothedAscentDescent` (NOT the raw per-point delta the older iOS port used);
 * this port follows the web reference, which the task designates as ground truth.
 */
object TrackStatsComputer {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEG2RAD = Math.PI / 180.0
    private const val MOVING_SPEED_THRESHOLD_MPS = 0.5
    private const val MAX_PLAUSIBLE_SPEED_MPS = 150.0
    private const val ELEVATION_DEADBAND_M = 5.0

    /** Great-circle distance in metres between two lat/lng pairs (degrees). */
    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * DEG2RAD
        val dLng = (lng2 - lng1) * DEG2RAD
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * DEG2RAD) * cos(lat2 * DEG2RAD) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * Total ascent + descent with GPS-noise smoothing. Hold a reference elevation
     * and only commit a gain/loss once the signed change from that reference clears
     * `thresholdM`; then advance the reference. Web `smoothedAscentDescent`.
     */
    fun smoothedAscentDescent(
        points: List<TrackPoint>,
        thresholdM: Double = ELEVATION_DEADBAND_M,
    ): Pair<Double, Double> {
        var ascent = 0.0
        var descent = 0.0
        var ref: Double? = null
        for (p in points) {
            val e = p.ele?.takeIf { it.isFinite() } ?: continue
            val r = ref
            if (r == null) { ref = e; continue }
            val d = e - r
            when {
                d >= thresholdM -> { ascent += d; ref = e }
                d <= -thresholdM -> { descent += -d; ref = e }
                // else: within the noise band — hold the reference.
            }
        }
        return ascent to descent
    }

    /** JS `Math.round(n * f) / f` — half-up (ties toward +∞); java.lang.Math.round matches it. */
    private fun round(n: Double, dp: Int): Double {
        val f = Math.pow(10.0, dp.toDouble())
        return Math.round(n * f).toDouble() / f
    }

    fun computeStats(points: List<TrackPoint>): TrackStats {
        if (points.isEmpty()) {
            return TrackStats(
                distanceM = 0.0, durationTotalS = 0.0, durationMovingS = 0.0,
                ascentM = 0.0, descentM = 0.0, minEleM = null, maxEleM = null,
                avgSpeedMps = 0.0, maxSpeedMps = 0.0, pointCount = 0, elevationProfile = emptyList(),
            )
        }

        var distanceM = 0.0
        var durationMovingS = 0.0
        var maxSpeedMps = 0.0
        var minEleM: Double? = null
        var maxEleM: Double? = null
        val profile = ArrayList<ElevationSample>(points.size)

        var cumDist = 0.0
        for (i in points.indices) {
            val p = points[i]
            val e = p.ele?.takeIf { it.isFinite() }
            if (e != null) {
                val lo = minEleM
                val hi = maxEleM
                if (lo == null || e < lo) minEleM = e
                if (hi == null || e > hi) maxEleM = e
            }

            if (i > 0) {
                val prev = points[i - 1]
                val seg = haversineM(prev.lat, prev.lng, p.lat, p.lng)
                cumDist += seg
                distanceM += seg

                val dtS = (p.t - prev.t) / 1000.0
                if (dtS > 0) {
                    val speed = seg / dtS
                    if (speed >= MOVING_SPEED_THRESHOLD_MPS) durationMovingS += dtS
                    if (speed <= MAX_PLAUSIBLE_SPEED_MPS && speed > maxSpeedMps) maxSpeedMps = speed
                }
            }

            profile.add(ElevationSample(distM = cumDist, eleM = e))
        }

        var durationTotalS = 0.0
        val first = points.first()
        val last = points.last()
        if (last.t > first.t) durationTotalS = (last.t - first.t) / 1000.0

        val (ascentM, descentM) = smoothedAscentDescent(points)

        val durForAvg = if (durationMovingS > 0) durationMovingS else durationTotalS
        val avgSpeedMps = if (durForAvg > 0) distanceM / durForAvg else 0.0

        return TrackStats(
            distanceM = round(distanceM, 2),
            durationTotalS = round(durationTotalS, 3),
            durationMovingS = round(durationMovingS, 3),
            ascentM = round(ascentM, 2),
            descentM = round(descentM, 2),
            minEleM = minEleM,
            maxEleM = maxEleM,
            avgSpeedMps = round(avgSpeedMps, 4),
            maxSpeedMps = round(maxSpeedMps, 4),
            pointCount = points.size,
            elevationProfile = profile,
        )
    }

    /** Bounding box over the point list (web `assembleTrack`). Null for an empty list. */
    fun bbox(points: List<TrackPoint>): TrackBBox? {
        if (points.isEmpty()) return null
        var minLat = Double.POSITIVE_INFINITY
        var minLng = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var maxLng = Double.NEGATIVE_INFINITY
        for (p in points) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lng < minLng) minLng = p.lng
            if (p.lng > maxLng) maxLng = p.lng
        }
        return TrackBBox(minLat = minLat, minLng = minLng, maxLat = maxLat, maxLng = maxLng)
    }
}
