package de.ledgerline.app.core.passkey

import java.security.MessageDigest

/**
 * WebAuthn authenticator-data + none-attestation encoders, byte-compatible with the web
 * (`passkey.js`) and iOS (`WebAuthn.swift`/`AttestationObject.swift`/`JWKP256.swift`). All CBOR is
 * hand-rolled (CTAP2 canonical), so no CBOR dependency is needed and the bytes are exact.
 */
object WebAuthnCbor {

    /** Authenticator-data flags. create = UP|UV|BE|BS|AT; assert = UP|UV|BE|BS. */
    const val FLAGS_CREATE: Int = 0x5D
    const val FLAGS_ASSERT: Int = 0x1D

    /** SHA-256 of the ASCII relying-party id. */
    fun rpIdHash(rpId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray(Charsets.UTF_8))

    /**
     * Authenticator data: `rpIdHash(32) ‖ flags(1) ‖ signCount(4 BE)` and, when
     * [attestedCredential] is present, `‖ aaguid(16) ‖ credIdLen(2 BE) ‖ credentialId ‖ cosePublicKey`.
     */
    fun authData(
        rpId: String,
        flags: Int,
        signCount: Long,
        attestedCredential: Triple<ByteArray, ByteArray, ByteArray>?, // (aaguid, credentialId, cosePublicKey)
    ): ByteArray {
        val out = ArrayList<Byte>(37)
        out.addAll(rpIdHash(rpId).toList())
        out.add((flags and 0xFF).toByte())
        val sc = signCount.toInt()
        out.add(((sc ushr 24) and 0xFF).toByte())
        out.add(((sc ushr 16) and 0xFF).toByte())
        out.add(((sc ushr 8) and 0xFF).toByte())
        out.add((sc and 0xFF).toByte())
        if (attestedCredential != null) {
            val (aaguid, credentialId, cose) = attestedCredential
            out.addAll(aaguid.toList())
            val idLen = credentialId.size
            out.add(((idLen ushr 8) and 0xFF).toByte())
            out.add((idLen and 0xFF).toByte())
            out.addAll(credentialId.toList())
            out.addAll(cose.toList())
        }
        return out.toByteArray()
    }

    /** Registration authData: flags 0x5D, signCount 0, AAGUID = 16 zero bytes. */
    fun authDataForCreate(rpId: String, credentialId: ByteArray, cosePublicKey: ByteArray): ByteArray =
        authData(rpId, FLAGS_CREATE, 0, Triple(ByteArray(16), credentialId, cosePublicKey))

    /** Authentication authData: flags 0x1D, signCount 0, no attested credential data. */
    fun authDataForAssert(rpId: String): ByteArray =
        authData(rpId, FLAGS_ASSERT, 0, null)

    /**
     * COSE_Key (CTAP2) for a P-256 public key given its 32-byte X and Y coordinates.
     * Map(5), key order 1,3,-1,-2,-3: kty=EC2, alg=ES256(-7), crv=P-256(1), x bstr(32), y bstr(32).
     */
    fun cosePublicKey(x: ByteArray, y: ByteArray): ByteArray {
        require(x.size == 32 && y.size == 32) { "P-256 coordinates must be 32 bytes" }
        val out = ArrayList<Byte>(77)
        out.add(0xA5.toByte())                 // map(5)
        out.addAll(listOf(0x01, 0x02))         // 1(kty) = 2(EC2)
        out.addAll(listOf(0x03, 0x26))         // 3(alg) = -7(ES256)
        out.addAll(listOf(0x20, 0x01))         // -1(crv) = 1(P-256)
        out.addAll(listOf(0x21, 0x58, 0x20))   // -2(x) = bstr(32)
        out.addAll(x.toList())
        out.addAll(listOf(0x22, 0x58, 0x20))   // -3(y) = bstr(32)
        out.addAll(y.toList())
        return out.map { it.toByte() }.toByteArray()
    }

    /** None-attestation object CBOR: `{"fmt":"none","attStmt":{},"authData":<bytes>}` (map order fmt,attStmt,authData). */
    fun attestationObjectNone(authData: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0xA3.toByte())                 // map(3)
        out.addAll(cborText("fmt").toList()); out.addAll(cborText("none").toList())
        out.addAll(cborText("attStmt").toList()); out.add(0xA0.toByte()) // empty map
        out.addAll(cborText("authData").toList()); out.addAll(cborBytes(authData).toList())
        return out.toByteArray()
    }

    private fun cborText(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return cborHeader(3, b.size) + b
    }

    private fun cborBytes(b: ByteArray): ByteArray = cborHeader(2, b.size) + b

    private fun cborHeader(majorType: Int, length: Int): ByteArray {
        val base = (majorType shl 5)
        return when {
            length <= 23 -> byteArrayOf((base or length).toByte())
            length <= 255 -> byteArrayOf((base or 24).toByte(), length.toByte())
            else -> byteArrayOf((base or 25).toByte(), ((length ushr 8) and 0xFF).toByte(), (length and 0xFF).toByte())
        }
    }

    private fun ArrayList<Byte>.addAll(ints: List<Int>) { ints.forEach { add((it and 0xFF).toByte()) } }
}
