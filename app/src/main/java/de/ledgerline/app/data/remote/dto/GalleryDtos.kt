package de.ledgerline.app.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ProcessResponse(
    val thumb: String? = null,
    val medium: String? = null,
    val motion: String? = null,
    val exif: JsonElement? = null,
    val place: JsonElement? = null,
    val embedding: JsonElement? = null,
    val phash: JsonElement? = null,   // server sends a signed 64-bit number, not a string
    val faces: List<ProcessFace> = emptyList(),
    val width: Int? = null,
    val height: Int? = null,
    val duration: Double? = null,
    val content_id: String? = null,
    /** CLIP model the embedding was produced with (server `gallery.ml_clip_model`). Tagged
     *  onto the sealed meta as `embModel` so search only compares same-model embeddings. */
    val model: String? = null,
)

/** `POST /gallery/analyze` response: deferred ML pass — CLIP embedding + faces only, plus the
 *  model tag. Same transient-plaintext ZK window as /gallery/process. */
@Serializable
data class AnalyzeResponse(
    val embedding: JsonElement? = null,
    val model: String? = null,
    val faces: List<ProcessFace> = emptyList(),
)

@Serializable
data class ProcessFace(
    val score: Double? = null,
    val box: JsonElement? = null,
    val embedding: JsonElement? = null,
    val crop: String? = null,
)

/** `GET /gallery/reverse` response: a resolved place display + structured address parts. */
@Serializable
data class ReverseResponse(
    val place: String? = null,
    val address: Map<String, String> = emptyMap(),
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

/** `POST /gallery/embed-text` request: the free-text query to embed. */
@Serializable
data class EmbedTextRequest(val q: String)

/** `POST /gallery/embed-text` response: the CLIP text embedding for the query. */
@Serializable
data class EmbedTextResponse(val embedding: List<Double> = emptyList())
