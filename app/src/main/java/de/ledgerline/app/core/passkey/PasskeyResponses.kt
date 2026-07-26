package de.ledgerline.app.core.passkey

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Builds the WebAuthn response JSON strings the Android Credential Manager expects from a passkey
 * provider (`registrationResponseJson` for create, `authenticationResponseJson` for get). Shapes
 * follow the W3C WebAuthn JSON serialization (base64url, no padding), matching what a browser/RP
 * parses. Pure — no Android types — so it's unit-testable.
 */
object PasskeyResponses {

    private fun b64u(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** `PublicKeyCredential` (registration) JSON: id/rawId + response{clientDataJSON,attestationObject}. */
    fun registration(
        credentialId: ByteArray,
        attestationObject: ByteArray,
        clientDataJson: ByteArray,
        transports: List<String> = listOf("internal", "hybrid"),
    ): String = jsonString(buildJsonObject {
        val id = b64u(credentialId)
        put("id", id)
        put("rawId", id)
        put("type", "public-key")
        put("authenticatorAttachment", "platform")
        put("clientExtensionResults", JsonObject(emptyMap()))
        put("response", buildJsonObject {
            put("clientDataJSON", b64u(clientDataJson))
            put("attestationObject", b64u(attestationObject))
            put("transports", kotlinx.serialization.json.JsonArray(transports.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })
    })

    /** `PublicKeyCredential` (authentication) JSON: id/rawId + response{clientDataJSON,authenticatorData,signature,userHandle}. */
    fun authentication(
        credentialId: ByteArray,
        authenticatorData: ByteArray,
        signature: ByteArray,
        clientDataJson: ByteArray,
        userHandle: ByteArray,
    ): String = jsonString(buildJsonObject {
        val id = b64u(credentialId)
        put("id", id)
        put("rawId", id)
        put("type", "public-key")
        put("authenticatorAttachment", "platform")
        put("clientExtensionResults", JsonObject(emptyMap()))
        put("response", buildJsonObject {
            put("clientDataJSON", b64u(clientDataJson))
            put("authenticatorData", b64u(authenticatorData))
            put("signature", b64u(signature))
            put("userHandle", b64u(userHandle))
        })
    })

    /**
     * The `clientDataJSON` bytes for a ceremony when the caller did NOT supply a clientDataHash
     * (app callers): `{type,challenge(b64url),origin,crossOrigin:false}`. Browsers instead supply
     * a clientDataHash and this is unused.
     */
    fun clientDataJson(type: String, challengeB64Url: String, origin: String): ByteArray = jsonString(buildJsonObject {
        put("type", type)
        put("challenge", challengeB64Url)
        put("origin", origin)
        put("crossOrigin", false)
    }).toByteArray(Charsets.UTF_8)

    private fun jsonString(o: JsonObject): String = o.toString()
}
