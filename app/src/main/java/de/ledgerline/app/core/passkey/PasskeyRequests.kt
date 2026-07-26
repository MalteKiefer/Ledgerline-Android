package de.ledgerline.app.core.passkey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses the WebAuthn creation/request options JSON passed by the Credential Manager. */
object PasskeyRequests {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parsed `PublicKeyCredentialCreationOptions`. Challenge is the raw base64url string. */
    data class Create(
        val rpId: String,
        val rpName: String,
        val challengeB64Url: String,
        val userId: ByteArray,
        val userName: String,
        val userDisplayName: String,
    )

    /** Parsed `PublicKeyCredentialRequestOptions`. */
    data class Get(
        val rpId: String,
        val challengeB64Url: String,
        val allowCredentialIds: List<ByteArray>,
    )

    fun parseCreate(requestJson: String): Create? = runCatching {
        val o = json.parseToJsonElement(requestJson).jsonObject
        val rp = o["rp"]?.jsonObject
        val user = o["user"]?.jsonObject
        Create(
            rpId = rp?.get("id")?.jsonPrimitive?.content ?: return@runCatching null,
            rpName = rp["name"]?.jsonPrimitive?.content ?: rp["id"]!!.jsonPrimitive.content,
            challengeB64Url = o["challenge"]?.jsonPrimitive?.content ?: return@runCatching null,
            userId = P256Key.b64uDecode(user?.get("id")?.jsonPrimitive?.content ?: return@runCatching null),
            userName = user["name"]?.jsonPrimitive?.content ?: "",
            userDisplayName = user["displayName"]?.jsonPrimitive?.content ?: "",
        )
    }.getOrNull()

    fun parseGet(requestJson: String): Get? = runCatching {
        val o = json.parseToJsonElement(requestJson).jsonObject
        val allow = (o["allowCredentials"] as? JsonArray)?.mapNotNull { e ->
            (e as? JsonObject)?.get("id")?.jsonPrimitive?.content?.let { runCatching { P256Key.b64uDecode(it) }.getOrNull() }
        } ?: emptyList()
        Get(
            rpId = o["rpId"]?.jsonPrimitive?.content ?: return@runCatching null,
            challengeB64Url = o["challenge"]?.jsonPrimitive?.content ?: return@runCatching null,
            allowCredentialIds = allow,
        )
    }.getOrNull()
}
