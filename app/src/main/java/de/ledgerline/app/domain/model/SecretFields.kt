package de.ledgerline.app.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Typed accessors over a [SecretItem]'s opaque `fields` JSON. */
object SecretFields {
    fun str(item: SecretItem, key: String): String =
        (item.fields[key] as? JsonPrimitive)?.contentOrNull ?: ""

    fun bool(item: SecretItem, key: String): Boolean =
        (item.fields[key] as? JsonPrimitive)?.booleanOrNull ?: false

    fun urls(item: SecretItem): List<String> =
        (item.fields["urls"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() } ?: emptyList()

    /** The primary subtitle shown in the list (username / host / etc. by type). */
    fun subtitle(item: SecretItem): String = when (item.type) {
        "login" -> str(item, "username").ifBlank { urls(item).firstOrNull().orEmpty() }
        "card" -> str(item, "number").takeLast(4).let { if (it.isNotBlank()) "•••• $it" else "" }
        "wifi" -> str(item, "ssid")
        "server" -> str(item, "host")
        "identity" -> listOf(str(item, "firstName"), str(item, "lastName")).filter { it.isNotBlank() }.joinToString(" ")
        "license" -> str(item, "product")
        else -> ""
    }

    /**
     * Build a `fields` JSON from flat edit values (+ the login `urls` list), preserving any
     * unknown keys already on [existing] (lossless round-trip like the web/iOS clients).
     */
    fun build(existing: JsonObject, type: String, values: Map<String, String>, urls: List<String>): JsonObject =
        buildJsonObject {
            // keep unknown keys from the original record
            existing.forEach { (k, v) -> if (k != "urls" && k !in values.keys) put(k, v) }
            values.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            if (type == "login") put("urls", buildJsonArray { urls.filter { it.isNotBlank() }.forEach { add(JsonPrimitive(it)) } })
            (existing["hidden"] as? JsonPrimitive)?.booleanOrNull?.let { if ("hidden" !in values) put("hidden", JsonPrimitive(it)) }
        }
}
