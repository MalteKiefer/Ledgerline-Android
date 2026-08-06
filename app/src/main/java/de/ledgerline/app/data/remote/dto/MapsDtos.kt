package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** `GET /gallery/reverse` response: a resolved place display + structured address parts.
 *  `address` is polymorphic on the wire — the server emits `{}` (object) when populated but `[]`
 *  (empty JSON array, PHP `json_encode([])`) when no structured address resolves, so it is typed as
 *  a raw [JsonElement] and read defensively — decoding it as a strict Map would throw on `[]` and
 *  silently discard an otherwise-usable `place` display name. Despite the path, this reverse-geocode
 *  is used by Explore's map (not just the removed photo gallery). */
@Serializable
data class ReverseResponse(
    val place: String? = null,
    val address: JsonElement? = null,
)

/**
 * `GET /maps/route` response: snapped path geometry as `[[lat,lng],…]` (null on fallback),
 * plus distance/duration and, when the engine supports it (GraphHopper), ascent/descent.
 */
@Serializable
data class MapsRouteResponse(
    val geometry: List<List<Double>>? = null,
    val distanceM: Double? = null,
    val durationS: Double? = null,
    val ascentM: Double? = null,
    val descentM: Double? = null,
)

/** `GET /maps/resolve` response: coordinates a Google-Maps short link points at (null when not resolvable). */
@Serializable
data class MapsResolveResponse(val lat: Double? = null, val lng: Double? = null)

/** `GET /gallery/geocode?q=` response: up to 6 forward-geocode candidates (server-proxied,
 *  never third-party-direct → keeps the query + client IP inside the ZK perimeter). Despite the
 *  path, this is a general place search used by Explore's map search + place lookups, not just
 *  the (removed) photo gallery. */
@Serializable
data class GeocodeResponse(val results: List<GeocodeHit> = emptyList())

@Serializable
data class GeocodeHit(
    val display: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
)
