package de.ledgerline.app.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The web client stores `trashed` as `false`/`null` when live and as an ISO timestamp
 * STRING when trashed (`trash(x){ x.trashed = new Date().toISOString() }`,
 * `restore(x){ x.trashed = null }`). A strict `Boolean` field throws on the string form
 * and takes the whole manifest down, so read it leniently:
 *  - a JSON boolean → its value,
 *  - null/absent → false,
 *  - any non-blank string → true (trashed),
 * and write it back as a plain boolean (the web treats truthy as trashed).
 *
 * Note: normalising the timestamp to `true` on our writes drops the "trashed at" date
 * the web shows in its trash bin; functionally the item stays trashed/restorable.
 */
object FlexibleTrashedSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("trashed", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val json = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val el = json.decodeJsonElement()
        return when {
            el is JsonNull -> false
            el is JsonPrimitive && el.isString -> el.content.isNotBlank()
            el is JsonPrimitive -> el.booleanOrNull ?: false
            else -> false
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}
