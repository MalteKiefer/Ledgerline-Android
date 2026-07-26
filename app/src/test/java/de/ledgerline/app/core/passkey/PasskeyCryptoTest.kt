package de.ledgerline.app.core.passkey

import de.ledgerline.app.domain.model.SecretItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

class PasskeyCryptoTest {

    private fun p256Params(): ECParameterSpec {
        val ap = AlgorithmParameters.getInstance("EC"); ap.init(ECGenParameterSpec("secp256r1"))
        return ap.getParameterSpec(ECParameterSpec::class.java)
    }

    @Test fun cosePublicKey_has_exact_ctap2_layout() {
        val x = ByteArray(32) { 0x11 }
        val y = ByteArray(32) { 0x22 }
        val cose = WebAuthnCbor.cosePublicKey(x, y)
        assertEquals(77, cose.size)
        val prefix = byteArrayOf(0xa5.toByte(), 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21, 0x58, 0x20)
        assertArrayEquals(prefix, cose.copyOfRange(0, 10))
        assertArrayEquals(x, cose.copyOfRange(10, 42))
        assertArrayEquals(byteArrayOf(0x22, 0x58, 0x20), cose.copyOfRange(42, 45))
        assertArrayEquals(y, cose.copyOfRange(45, 77))
    }

    @Test fun authData_create_layout_and_flags() {
        val cred = ByteArray(16) { 0x33 }
        val cose = WebAuthnCbor.cosePublicKey(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val ad = WebAuthnCbor.authDataForCreate("example.com", cred, cose)
        // 32 rpIdHash + 1 flags + 4 signCount + 16 aaguid + 2 credLen + 16 cred + 77 cose
        assertEquals(32 + 1 + 4 + 16 + 2 + 16 + 77, ad.size)
        assertArrayEquals(WebAuthnCbor.rpIdHash("example.com"), ad.copyOfRange(0, 32))
        assertEquals(0x5D.toByte(), ad[32])
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), ad.copyOfRange(33, 37)) // signCount 0
        assertArrayEquals(byteArrayOf(0, 16), ad.copyOfRange(53, 55))      // credIdLen = 16
    }

    @Test fun attestationObject_none_structure() {
        val ad = byteArrayOf(1, 2, 3)
        val att = WebAuthnCbor.attestationObjectNone(ad)
        // a3 63 "fmt" 64 "none" 67 "attStmt" a0 68 "authData" 43 01 02 03
        val expected = byteArrayOf(
            0xA3.toByte(),
            0x63, 'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(),
            0x64, 'n'.code.toByte(), 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
            0x67, 'a'.code.toByte(), 't'.code.toByte(), 't'.code.toByte(), 'S'.code.toByte(), 't'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(),
            0xA0.toByte(),
            0x68, 'a'.code.toByte(), 'u'.code.toByte(), 't'.code.toByte(), 'h'.code.toByte(), 'D'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            0x43, 1, 2, 3,
        )
        assertArrayEquals(expected, att)
    }

    @Test fun generate_sign_verify_roundtrip() {
        val g = P256Key.generate()
        val msg = "hello-webauthn".toByteArray()
        val der = P256Key.sign(g.privateJwk, msg)

        // Rebuild the public key from the JWK coordinates and verify the DER signature.
        val pub = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(BigInteger(1, g.x), BigInteger(1, g.y)), p256Params()),
        )
        val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(msg); verify(der) }
        assertTrue(ok)
        // publicCoordinates recovers the same x/y from the private JWK.
        val (x2, y2) = P256Key.publicCoordinates(g.privateJwk)
        assertArrayEquals(g.x, x2); assertArrayEquals(g.y, y2)
    }

    @Test fun rpIdAllowed_rules() {
        assertTrue(PasskeyStore.rpIdAllowed("example.com"))
        assertTrue(PasskeyStore.rpIdAllowed("login.example.co.uk"))
        assertFalse(PasskeyStore.rpIdAllowed("localhost"))
        assertFalse(PasskeyStore.rpIdAllowed(".example.com"))
        assertFalse(PasskeyStore.rpIdAllowed("a..b.com"))
        assertFalse(PasskeyStore.rpIdAllowed(" example.com"))
    }

    @Test fun standalone_and_candidates_roundtrip() {
        val g = P256Key.generate()
        val cred = ByteArray(16) { 0x44 }
        val uh = ByteArray(8) { 0x55 }
        val item = PasskeyStore.standaloneItem(
            rpId = "example.com", rpName = "Example", credentialId = cred, privateKeyJwk = g.privateJwk,
            publicKeyJwk = g.publicJwk, userHandle = uh, userName = "u@example.com", userDisplayName = "U", now = "2026-07-26T00:00:00Z",
        )
        assertEquals("passkey", item.type)
        assertEquals(-7, item.fields.getValue("alg").jsonPrimitive.content.toInt())

        val cands = PasskeyStore.candidates("example.com", listOf(item))
        assertEquals(1, cands.size)
        assertArrayEquals(cred, cands[0].credentialId)
        assertArrayEquals(uh, cands[0].userHandle)
        // Excluded credential id → filtered out.
        assertTrue(PasskeyStore.candidates("example.com", listOf(item), listOf(cred)).isEmpty())
        // Wrong rpId → none.
        assertTrue(PasskeyStore.candidates("evil.com", listOf(item)).isEmpty())
    }

    @Test fun attach_to_login_embeds_passkey() {
        val g = P256Key.generate()
        val login = SecretItem(id = "L1", type = "login", title = "Example")
        val updated = PasskeyStore.attach(
            loginId = "L1", rpId = "example.com", rpName = "Example", items = listOf(login),
            credentialId = ByteArray(16) { 1 }, privateKeyJwk = g.privateJwk, publicKeyJwk = g.publicJwk,
            userHandle = ByteArray(8) { 2 }, userName = "u", userDisplayName = "U", now = "2026-07-26T00:00:00Z",
        )
        val arr = updated[0].fields["passkeys"] as JsonArray
        assertEquals(1, arr.size)
        assertEquals("example.com", (arr[0] as JsonObject).getValue("rpId").jsonPrimitive.content)
        // candidates() finds the embedded passkey with Embedded source.
        val cands = PasskeyStore.candidates("example.com", updated)
        assertEquals(1, cands.size)
        assertTrue(cands[0].source is PasskeyStore.Source.Embedded)
    }
}
