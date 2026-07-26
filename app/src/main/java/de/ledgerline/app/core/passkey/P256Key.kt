package de.ledgerline.app.core.passkey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * P-256 (secp256r1 / ES256) key material for WebAuthn passkeys, using only the JCA (no libsodium,
 * no BouncyCastle). Keys are serialised as JWK JSON `{kty:"EC",crv:"P-256",d,x,y}` (base64url,
 * no padding) — byte-compatible with the web (`passkey.js` WebCrypto exportKey) and iOS
 * (`JWKP256.swift`). Signatures are ASN.1 DER-encoded ECDSA, as WebAuthn requires.
 */
object P256Key {

    /** A freshly generated keypair, exposed as coordinates + JWK strings. */
    data class Generated(
        val x: ByteArray,            // 32-byte affine X
        val y: ByteArray,            // 32-byte affine Y
        val privateJwk: String,      // {kty,crv,d,x,y}
        val publicJwk: String,       // {kty,crv,x,y}
    )

    private fun params(): ECParameterSpec {
        val ap = java.security.AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        return ap.getParameterSpec(ECParameterSpec::class.java)
    }

    fun generate(): Generated {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val kp = gen.generateKeyPair()
        val priv = kp.private as ECPrivateKey
        val pub = kp.public as ECPublicKey
        val d = fixed32(priv.s)
        val x = fixed32(pub.w.affineX)
        val y = fixed32(pub.w.affineY)
        return Generated(
            x = x, y = y,
            privateJwk = jwk(d = d, x = x, y = y),
            publicJwk = jwk(d = null, x = x, y = y),
        )
    }

    /** The 32-byte affine X/Y of a private-key JWK's public point (for the COSE key on assert reuse). */
    fun publicCoordinates(privateJwk: String): Pair<ByteArray, ByteArray> {
        val o = Json.parseToJsonElement(privateJwk).jsonObject
        return b64uDecode(o.getValue("x").jsonPrimitive.content) to b64uDecode(o.getValue("y").jsonPrimitive.content)
    }

    /** Sign `message` (authData ‖ clientDataHash) with the JWK private key → DER ECDSA bytes. */
    fun sign(privateJwk: String, message: ByteArray): ByteArray {
        val o = Json.parseToJsonElement(privateJwk).jsonObject
        require(o["kty"]?.jsonPrimitive?.content == "EC" && o["crv"]?.jsonPrimitive?.content == "P-256") { "not a P-256 JWK" }
        val d = BigInteger(1, b64uDecode(o.getValue("d").jsonPrimitive.content))
        val key = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, params()))
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(message)
            sign()
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private fun jwk(d: ByteArray?, x: ByteArray, y: ByteArray): String {
        // Hand-built to fix key order kty,crv,(d,)x,y — matches the web/iOS emitters.
        val sb = StringBuilder("{\"kty\":\"EC\",\"crv\":\"P-256\",")
        if (d != null) sb.append("\"d\":\"").append(b64u(d)).append("\",")
        sb.append("\"x\":\"").append(b64u(x)).append("\",")
        sb.append("\"y\":\"").append(b64u(y)).append("\"}")
        return sb.toString()
    }

    /** Left-pad/trim a BigInteger to exactly 32 bytes (drops a sign byte, pads short values). */
    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size == 33 && b[0].toInt() == 0 -> b.copyOfRange(1, 33)
            b.size < 32 -> ByteArray(32 - b.size) + b
            else -> b.copyOfRange(b.size - 32, b.size)
        }
    }

    private fun b64u(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun b64uDecode(s: String): ByteArray {
        // Tolerate optional padding (WebCrypto/iOS emit unpadded; some callers pad).
        val pad = (4 - s.length % 4) % 4
        return Base64.getUrlDecoder().decode(s + "=".repeat(pad))
    }
}
