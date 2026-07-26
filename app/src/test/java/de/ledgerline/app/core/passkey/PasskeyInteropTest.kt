package de.ledgerline.app.core.passkey

import de.ledgerline.app.domain.model.SecretItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/**
 * Cross-client interop for passkeys (§4.6): a passkey written by the web (`passwords.js`) must be
 * resolvable + signable by Android, and Android's stored passkey must carry exactly the web field
 * shape so web/iOS can use it. Uses a real generated P-256 key so the assertion actually verifies.
 */
class PasskeyInteropTest {

    private fun p256(): ECParameterSpec {
        val ap = AlgorithmParameters.getInstance("EC"); ap.init(ECGenParameterSpec("secp256r1"))
        return ap.getParameterSpec(ECParameterSpec::class.java)
    }

    /**
     * A web-`passwords.js`-shaped embedded passkey entry inside a login item's `fields.passkeys`.
     * Note `privateKey`/`publicKey` are JWK JSON **strings** (as web/iOS store them), not objects.
     */
    private fun webLoginWithPasskey(privJwk: String, pubJwk: String, credB64: String): SecretItem {
        val entry = kotlinx.serialization.json.buildJsonObject {
            put("rpId", kotlinx.serialization.json.JsonPrimitive("example.com"))
            put("credentialId", kotlinx.serialization.json.JsonPrimitive(credB64))
            put("alg", kotlinx.serialization.json.JsonPrimitive(-7))
            put("privateKey", kotlinx.serialization.json.JsonPrimitive(privJwk))
            put("publicKey", kotlinx.serialization.json.JsonPrimitive(pubJwk))
            put("userHandle", kotlinx.serialization.json.JsonPrimitive("dQ"))
            put("userName", kotlinx.serialization.json.JsonPrimitive("u@example.com"))
            put("userDisplayName", kotlinx.serialization.json.JsonPrimitive("U"))
            put("signCount", kotlinx.serialization.json.JsonPrimitive(0))
        }
        val fields = kotlinx.serialization.json.buildJsonObject {
            put("username", kotlinx.serialization.json.JsonPrimitive("u"))
            put("passkeys", kotlinx.serialization.json.JsonArray(listOf(entry)))
        }
        return SecretItem(id = "L1", type = "login", title = "Example", fields = fields)
    }

    @Test fun web_written_passkey_resolves_and_signs_on_android() {
        val g = P256Key.generate()
        val credB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 7 })
        val login = webLoginWithPasskey(g.privateJwk, g.publicJwk, credB64)

        val cands = PasskeyStore.candidates("example.com", listOf(login))
        assertEquals(1, cands.size)
        val pk = cands[0]
        assertTrue(pk.source is PasskeyStore.Source.Embedded)

        // Sign an assertion with the web-stored private JWK and verify with the web-stored public key.
        val authData = WebAuthnCbor.authDataForAssert("example.com")
        val clientDataHash = ByteArray(32) { 9 }
        val der = P256Key.sign(pk.privateKeyJwk, authData + clientDataHash)

        val pubJson = Json.parseToJsonElement(g.publicJwk).jsonObject
        val x = P256Key.b64uDecode(pubJson.getValue("x").jsonPrimitive.content)
        val y = P256Key.b64uDecode(pubJson.getValue("y").jsonPrimitive.content)
        val pub = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), p256()),
        )
        val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(authData + clientDataHash); verify(der) }
        assertTrue(ok)
    }

    @Test fun android_written_passkey_has_web_field_shape() {
        val g = P256Key.generate()
        val item = PasskeyStore.standaloneItem(
            rpId = "example.com", rpName = "Example", credentialId = ByteArray(16) { 1 },
            privateKeyJwk = g.privateJwk, publicKeyJwk = g.publicJwk, userHandle = ByteArray(8) { 2 },
            userName = "u", userDisplayName = "U", now = "2026-07-26T00:00:00Z",
        )
        val f: JsonObject = item.fields
        // Exactly the keys web/iOS read.
        listOf("rpId", "rpName", "credentialId", "alg", "privateKey", "publicKey", "userHandle", "userName", "userDisplayName", "signCount", "createdAt")
            .forEach { assertNotNull("missing $it", f[it]) }
        assertEquals(-7, f.getValue("alg").jsonPrimitive.content.toInt())
        assertEquals(0, f.getValue("signCount").jsonPrimitive.content.toInt())
        // credentialId + userHandle are base64url (decodable).
        assertEquals(16, P256Key.b64uDecode(f.getValue("credentialId").jsonPrimitive.content).size)
        assertEquals(8, P256Key.b64uDecode(f.getValue("userHandle").jsonPrimitive.content).size)
        // Private JWK is a P-256 EC key.
        val jwk = Json.parseToJsonElement(f.getValue("privateKey").jsonPrimitive.content).jsonObject
        assertEquals("EC", jwk.getValue("kty").jsonPrimitive.content)
        assertEquals("P-256", jwk.getValue("crv").jsonPrimitive.content)
    }
}
