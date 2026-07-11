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
)

@Serializable
data class ProcessFace(
    val score: Double? = null,
    val box: JsonElement? = null,
    val embedding: JsonElement? = null,
    val crop: String? = null,
)

/** `POST /gallery/embed-text` request: the free-text query to embed. */
@Serializable
data class EmbedTextRequest(val q: String)

/** `POST /gallery/embed-text` response: the CLIP text embedding for the query. */
@Serializable
data class EmbedTextResponse(val embedding: List<Double> = emptyList())
