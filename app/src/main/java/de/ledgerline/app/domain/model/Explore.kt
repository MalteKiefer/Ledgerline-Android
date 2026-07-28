package de.ledgerline.app.domain.model

import de.ledgerline.app.core.explore.ElevationSample
import de.ledgerline.app.core.explore.TrackBBox
import de.ledgerline.app.core.explore.TrackPoint
import de.ledgerline.app.core.explore.TrackStats
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * One saved track in the `explore` module store. Known fields are typed; the original decoded
 * JSON is kept in [raw] so foreign/future keys (rawBlobId, surfaces, couplings hints, imports…)
 * survive an Android read-modify-write — the same no-data-loss overlay as [FileRecordCodec].
 * Numbers are JSON numbers (Explore uses numeric lat/lng, unlike the gallery's decimal strings).
 */
data class ExploreTrack(
    val id: String,
    val name: String,
    val sourceFormat: String = "recorded",
    val activity: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val createdAt: String? = null,
    val note: String? = null,
    val points: List<TrackPoint> = emptyList(),
    val stats: TrackStats? = null,
    val bbox: TrackBBox? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** Decrypted `store/explore` manifest. [extra] preserves couplings/settings/v + unknown keys. */
data class ExploreManifest(
    val tracks: List<ExploreTrack> = emptyList(),
    val extra: JsonObject = JsonObject(emptyMap()),
)

/** Manifest + optimistic-concurrency version. */
data class ExploreStore(val manifest: ExploreManifest, val version: Int)

/** JSON (de)serialization for explore tracks, byte-shape aligned with web `track-parse.js`. */
object ExploreTrackCodec {
    private const val TRACKS = "tracks"
    private val OWNED = setOf(
        "id", "name", "sourceFormat", "activity", "startedAt", "endedAt", "createdAt", "note",
        "points", "stats", "bbox",
    )

    fun decodeManifest(root: JsonObject): ExploreManifest {
        val tracks = (root[TRACKS] as? JsonArray).orEmptyArray().mapNotNull { el ->
            (el as? JsonObject)?.let { decodeTrack(it) }
        }
        val extra = JsonObject(root.filterKeys { it != TRACKS })
        return ExploreManifest(tracks, extra)
    }

    fun encodeManifest(m: ExploreManifest): JsonObject = buildJsonObject {
        // Preserve foreign top-level keys (couplings/settings/…), then set v + tracks (owned win).
        m.extra.forEach { (k, v) -> put(k, v) }
        put("v", JsonPrimitive(3))
        put(TRACKS, JsonArray(m.tracks.map { encodeTrack(it) }))
    }

    private fun decodeTrack(o: JsonObject): ExploreTrack = ExploreTrack(
        id = o.str("id") ?: "",
        name = o.str("name") ?: "",
        sourceFormat = o.str("sourceFormat") ?: "recorded",
        activity = o.str("activity"),
        startedAt = o.str("startedAt"),
        endedAt = o.str("endedAt"),
        createdAt = o.str("createdAt"),
        note = o.str("note"),
        points = (o["points"] as? JsonArray).orEmptyArray().mapNotNull { p ->
            (p as? JsonObject)?.let {
                TrackPoint(
                    lat = it.dbl("lat") ?: return@mapNotNull null,
                    lng = it.dbl("lng") ?: return@mapNotNull null,
                    ele = it.dbl("ele"),
                    t = it["t"]?.jsonPrimitive?.long ?: 0L,
                )
            }
        },
        stats = (o["stats"] as? JsonObject)?.let { decodeStats(it) },
        bbox = (o["bbox"] as? JsonObject)?.let {
            TrackBBox(it.dbl("minLat") ?: 0.0, it.dbl("minLng") ?: 0.0, it.dbl("maxLat") ?: 0.0, it.dbl("maxLng") ?: 0.0)
        },
        raw = o,
    )

    /** Owned fields overlaid on the original [ExploreTrack.raw] so foreign keys survive. */
    fun encodeTrack(t: ExploreTrack): JsonObject = buildJsonObject {
        t.raw.forEach { (k, v) -> if (k !in OWNED) put(k, v) }
        put("id", JsonPrimitive(t.id))
        put("name", JsonPrimitive(t.name))
        put("sourceFormat", JsonPrimitive(t.sourceFormat))
        t.activity?.let { put("activity", JsonPrimitive(it)) }
        t.startedAt?.let { put("startedAt", JsonPrimitive(it)) }
        t.endedAt?.let { put("endedAt", JsonPrimitive(it)) }
        t.createdAt?.let { put("createdAt", JsonPrimitive(it)) }
        t.note?.let { put("note", JsonPrimitive(it)) }
        put("points", JsonArray(t.points.map { encodePoint(it) }))
        t.stats?.let { put("stats", encodeStats(it)) }
        t.bbox?.let { put("bbox", encodeBBox(it)) }
    }

    private fun encodePoint(p: TrackPoint): JsonObject = buildJsonObject {
        put("lat", JsonPrimitive(p.lat))
        put("lng", JsonPrimitive(p.lng))
        put("ele", p.ele?.let { JsonPrimitive(it) } ?: JsonNull)
        put("t", JsonPrimitive(p.t))
    }

    private fun encodeBBox(b: TrackBBox): JsonObject = buildJsonObject {
        put("minLat", JsonPrimitive(b.minLat)); put("minLng", JsonPrimitive(b.minLng))
        put("maxLat", JsonPrimitive(b.maxLat)); put("maxLng", JsonPrimitive(b.maxLng))
    }

    private fun decodeStats(o: JsonObject) = TrackStats(
        distanceM = o.dbl("distanceM") ?: 0.0,
        durationTotalS = o.dbl("durationTotalS") ?: 0.0,
        durationMovingS = o.dbl("durationMovingS") ?: 0.0,
        ascentM = o.dbl("ascentM") ?: 0.0,
        descentM = o.dbl("descentM") ?: 0.0,
        minEleM = o.dbl("minEleM"),
        maxEleM = o.dbl("maxEleM"),
        avgSpeedMps = o.dbl("avgSpeedMps") ?: 0.0,
        maxSpeedMps = o.dbl("maxSpeedMps") ?: 0.0,
        pointCount = (o["pointCount"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toInt(),
        elevationProfile = (o["elevationProfile"] as? JsonArray).orEmptyArray().mapNotNull { e ->
            (e as? JsonObject)?.let { ElevationSample(it.dbl("distM") ?: 0.0, it.dbl("eleM")) }
        },
    )

    private fun encodeStats(s: TrackStats): JsonObject = buildJsonObject {
        put("distanceM", JsonPrimitive(s.distanceM))
        put("durationTotalS", JsonPrimitive(s.durationTotalS))
        put("durationMovingS", JsonPrimitive(s.durationMovingS))
        put("ascentM", JsonPrimitive(s.ascentM))
        put("descentM", JsonPrimitive(s.descentM))
        put("minEleM", s.minEleM?.let { JsonPrimitive(it) } ?: JsonNull)
        put("maxEleM", s.maxEleM?.let { JsonPrimitive(it) } ?: JsonNull)
        put("avgSpeedMps", JsonPrimitive(s.avgSpeedMps))
        put("maxSpeedMps", JsonPrimitive(s.maxSpeedMps))
        put("pointCount", JsonPrimitive(s.pointCount))
        put("elevationProfile", JsonArray(s.elevationProfile.map { e ->
            buildJsonObject { put("distM", JsonPrimitive(e.distM)); put("eleM", e.eleM?.let { JsonPrimitive(it) } ?: JsonNull) }
        }))
    }

    private fun JsonArray?.orEmptyArray(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
    private fun JsonObject.str(k: String): String? {
        val el = this[k]
        if (el == null || el is JsonNull) return null
        return (el as? JsonPrimitive)?.content
    }
    private fun JsonObject.dbl(k: String): Double? {
        val el = this[k]
        if (el == null || el is JsonNull) return null
        return (el as? JsonPrimitive)?.doubleOrNull
    }
}
