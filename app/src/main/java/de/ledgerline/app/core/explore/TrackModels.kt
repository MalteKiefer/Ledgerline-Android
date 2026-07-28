package de.ledgerline.app.core.explore

/**
 * Pure track data models for the Explore module. Mirror the web
 * `resources/js/shared/track-parse.js` TrackPoint / stats shapes and the iOS
 * `ExploreModels.swift` structs. No Android framework dependencies.
 *
 * `t` is epoch MILLISECONDS.
 */
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val ele: Double? = null,
    val t: Long,
)

/** One point on the elevation-vs-distance profile. `distM` = cumulative metres. */
data class ElevationSample(
    val distM: Double,
    val eleM: Double?,
)

/** Geographic bounding box of a track. */
data class TrackBBox(
    val minLat: Double,
    val minLng: Double,
    val maxLat: Double,
    val maxLng: Double,
)

/** Summary statistics derived from an ordered point list. Byte-exact with web `computeStats`. */
data class TrackStats(
    val distanceM: Double,
    val durationTotalS: Double,
    val durationMovingS: Double,
    val ascentM: Double,
    val descentM: Double,
    val minEleM: Double?,
    val maxEleM: Double?,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val pointCount: Int,
    val elevationProfile: List<ElevationSample>,
)
