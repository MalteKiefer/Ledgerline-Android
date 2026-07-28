package de.ledgerline.app.core.explore

import de.ledgerline.app.domain.model.ExploreTrack
import kotlin.math.abs

/**
 * Pure photo→track matcher for the Explore module — a port of the web `shared/photo-track-match.js`.
 * Zero-knowledge, deterministic (no clock/random). Cascade: (1) EXIF GPS → `exif` (assign to a track
 * only when a point is within the distance tolerance; time is a tiebreaker), (2) no GPS → `interpolated`
 * (assign by time span, position interpolated along the track), (3) neither → `none`.
 */
object PhotoTrackMatch {

    data class Pos(val lat: Double, val lng: Double, val ele: Double?)
    data class Match(val trackId: String?, val source: String, val lat: Double? = null, val lng: Double? = null)

    /** Linear interpolation of a position along [points] (time-ordered) at epoch-ms [t], or null. */
    fun interpolatePosition(points: List<de.ledgerline.app.core.explore.TrackPoint>, t: Long): Pos? {
        if (points.isEmpty()) return null
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            if (t < a.t || t > b.t) continue
            val span = b.t - a.t
            val frac = if (span > 0) (t - a.t).toDouble() / span else 0.0
            fun lerp(x: Double, y: Double) = x + (y - x) * frac
            val ele = when {
                a.ele != null && b.ele != null -> lerp(a.ele, b.ele)
                a.ele != null -> a.ele
                else -> b.ele
            }
            return Pos(lerp(a.lat, b.lat), lerp(a.lng, b.lng), ele)
        }
        for (p in points) if (p.t == t) return Pos(p.lat, p.lng, p.ele)
        return null
    }

    private fun timeSpan(points: List<de.ledgerline.app.core.explore.TrackPoint>): Pair<Long, Long>? {
        if (points.isEmpty()) return null
        var min = Long.MAX_VALUE; var max = Long.MIN_VALUE
        for (p in points) { if (p.t < min) min = p.t; if (p.t > max) max = p.t }
        return min to max
    }

    /**
     * Match one photo (nullable GPS + epoch-ms time) to the best track. [timeToleranceS]/[distanceToleranceM]
     * bound the time/distance windows (web defaults 3600 s / 100 m).
     */
    fun matchPhotoToTracks(
        photoLat: Double?, photoLng: Double?, photoTime: Long?,
        tracks: List<ExploreTrack>, timeToleranceS: Int, distanceToleranceM: Int,
    ): Match {
        val timeTolMs = maxOf(0, timeToleranceS) * 1000L
        val distTolM = maxOf(0, distanceToleranceM).toDouble()
        val hasGps = photoLat != null && photoLat.isFinite() && photoLng != null && photoLng.isFinite()
        val hasTime = photoTime != null

        // 1) EXIF GPS: nearest track point within the distance tolerance; time is only a tiebreaker.
        if (hasGps) {
            var bestTrack: String? = null; var bestDist = Double.MAX_VALUE; var bestDt = Double.MAX_VALUE
            for (track in tracks) {
                for (p in track.points) {
                    val d = TrackStatsComputer.haversineM(photoLat!!, photoLng!!, p.lat, p.lng)
                    if (d > distTolM) continue
                    val dtMs = if (hasTime) abs(p.t - photoTime!!).toDouble() else Double.MAX_VALUE
                    if (bestTrack == null || d < bestDist || (d == bestDist && dtMs < bestDt)) {
                        bestTrack = track.id; bestDist = d; bestDt = dtMs
                    }
                }
            }
            return if (bestTrack != null) Match(bestTrack, "exif", photoLat, photoLng)
            else Match(null, "exif", photoLat, photoLng)   // GPS but no track nearby: still EXIF-positioned
        }

        // 2) No GPS: assign by time span, interpolate the position.
        if (hasTime) {
            var bestTrack: String? = null; var bestPos: Pos? = null; var bestDt = Long.MAX_VALUE
            for (track in tracks) {
                val span = timeSpan(track.points) ?: continue
                val (minT, maxT) = span
                if (photoTime!! < minT - timeTolMs || photoTime > maxT + timeTolMs) continue
                val pos = interpolatePosition(track.points, photoTime) ?: continue
                val dt = minOf(abs(photoTime - minT), abs(photoTime - maxT))
                if (bestTrack == null || dt < bestDt) { bestTrack = track.id; bestPos = pos; bestDt = dt }
            }
            if (bestTrack != null && bestPos != null) return Match(bestTrack, "interpolated", bestPos!!.lat, bestPos!!.lng)
        }

        return Match(null, "none")
    }
}
