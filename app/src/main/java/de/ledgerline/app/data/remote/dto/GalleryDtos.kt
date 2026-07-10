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
    val phash: String? = null,
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
