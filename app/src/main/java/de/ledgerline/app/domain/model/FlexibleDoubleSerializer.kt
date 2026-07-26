package de.ledgerline.app.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decodes a `Double?` that may arrive as a JSON **string** or number — gallery store
 * records carry `lat`/`lng` as `dec6` strings ("52.520008") like the web client, but
 * legacy/inline data may have plain numbers. Encoding is plain (the record codec renders
 * lat/lng as dec6 strings itself, so this side is only a fallback).
 */
object FlexibleDoubleSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleDouble", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Double? {
        val jd = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        val el = jd.decodeJsonElement()
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.content?.toDoubleOrNull()
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}
