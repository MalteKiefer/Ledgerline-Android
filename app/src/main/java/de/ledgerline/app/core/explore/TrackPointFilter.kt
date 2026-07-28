package de.ledgerline.app.core.explore

/**
 * Pure GPS-fix acceptance rule — no location-framework dependency so it is
 * unit-testable. Mirrors iOS `Explore/TrackPointFilter.swift`.
 */
object TrackPointFilter {
    /**
     * Accept a candidate fix only if:
     * - `horizontalAccuracyM` is in `0.0..50.0` (negative = invalid; >50 = too imprecise), AND
     * - it is the first point (`prev == null`) OR it moved ≥ 1 m from `prev`
     *   (rejects stationary duplicates).
     */
    fun accept(
        prev: TrackPoint?,
        candidateLat: Double,
        candidateLng: Double,
        horizontalAccuracyM: Double,
    ): Boolean {
        if (horizontalAccuracyM < 0.0 || horizontalAccuracyM > 50.0) return false
        if (prev == null) return true
        val moved = TrackGeometry.haversine(prev.lat, prev.lng, candidateLat, candidateLng)
        return moved >= 1.0
    }
}
