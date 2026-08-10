package de.ledgerline.app.push

import de.ledgerline.app.data.remote.dto.PushPayload
import kotlinx.serialization.json.Json

/** Pure, Android-free push logic so it can be unit-tested without Robolectric. */
object PushFilter {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Decode a raw endpoint payload, or null if it isn't valid JSON for a [PushPayload]. */
    fun parse(bytes: ByteArray): PushPayload? =
        runCatching { json.decodeFromString(PushPayload.serializer(), String(bytes, Charsets.UTF_8)) }.getOrNull()

    /** Whether a payload of [category] should surface, given the user's push state. */
    fun shouldShow(enabled: Boolean, muted: Set<String>, category: String): Boolean =
        enabled && (category.isBlank() || category !in muted)
}
