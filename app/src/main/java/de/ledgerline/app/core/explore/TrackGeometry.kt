package de.ledgerline.app.core.explore

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry helpers over an ordered GPS track. Mirrors iOS
 * `Explore/TrackGeometry.swift`.
 */
object TrackGeometry {
    /** Great-circle distance in metres between two lat/lng pairs (degrees). */
    fun haversine(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 6_371_000.0
        val dLat = (bLat - aLat) * Math.PI / 180
        val dLng = (bLng - aLng) * Math.PI / 180
        val la1 = aLat * Math.PI / 180
        val la2 = bLat * Math.PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    /**
     * The GPS coordinate at `distM` metres of cumulative great-circle distance
     * along `points`, linearly interpolated within the containing segment. Clamped
     * to the ends. Returns the single point for a 1-point track, null for empty.
     * (Used to drive an elevation-chart hover marker on the map.)
     */
    fun interpolateAtDistance(points: List<TrackPoint>, distM: Double): Pair<Double, Double>? {
        val first = points.firstOrNull() ?: return null
        if (points.size < 2) return first.lat to first.lng
        if (distM <= 0) return first.lat to first.lng

        var acc = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val seg = haversine(a.lat, a.lng, b.lat, b.lng)
            if (acc + seg >= distM) {
                val f = if (seg > 0) (distM - acc) / seg else 0.0
                return (a.lat + (b.lat - a.lat) * f) to (a.lng + (b.lng - a.lng) * f)
            }
            acc += seg
        }
        val last = points[points.size - 1]
        return last.lat to last.lng
    }
}
