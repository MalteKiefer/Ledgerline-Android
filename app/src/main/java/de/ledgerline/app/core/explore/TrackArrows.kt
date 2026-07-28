package de.ledgerline.app.core.explore

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Direction-of-travel arrows for a track — a faithful port of the web `_directionArrows`. Places
 * 4..40 arrows spaced ~evenly by distance along the track, each pointing in the local travel
 * direction (compass bearing). Pure + unit-testable; the map layer just renders the result.
 */
object TrackArrows {

    /** One arrow: a position on the track and the compass bearing (deg, 0 = N, clockwise) to point. */
    data class Arrow(val lat: Double, val lng: Double, val bearingDeg: Double)

    /** Compass bearing from a → b in degrees (0 = north, clockwise). */
    fun bearing(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLng = Math.toRadians(bLng - aLng)
        val la1 = Math.toRadians(aLat)
        val la2 = Math.toRadians(bLat)
        val y = sin(dLng) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Arrow positions + bearings along [points]; empty when the track is too short (< 80 m). */
    fun compute(points: List<TrackPoint>): List<Arrow> {
        val pts = points.filter { it.lat.isFinite() && it.lng.isFinite() }
        if (pts.size < 2) return emptyList()
        val cum = DoubleArray(pts.size)
        var total = 0.0
        for (i in 1 until pts.size) {
            total += TrackGeometry.haversine(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng)
            cum[i] = total
        }
        if (total < 80.0) return emptyList()

        val count = min(40, max(4, (total / 400.0).roundToInt()))
        val step = total / (count + 1)
        val out = ArrayList<Arrow>(count)
        var seg = 1
        for (k in 1..count) {
            val target = step * k
            while (seg < pts.size && cum[seg] < target) seg++
            if (seg >= pts.size) break
            val a = pts[seg - 1]
            val b = pts[seg]
            val span = cum[seg] - cum[seg - 1]
            val t = if (span > 0) (target - cum[seg - 1]) / span else 0.0
            out.add(
                Arrow(
                    lat = a.lat + (b.lat - a.lat) * t,
                    lng = a.lng + (b.lng - a.lng) * t,
                    bearingDeg = bearing(a.lat, a.lng, b.lat, b.lng),
                ),
            )
        }
        return out
    }
}
