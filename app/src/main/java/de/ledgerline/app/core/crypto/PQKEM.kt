package de.ledgerline.app.core.crypto

import android.util.Base64
import com.goterl.lazysodium.SodiumAndroid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ML-KEM-768 (FIPS 203) PQ leg, isolated from any native/libsodium dependency so it
 * is unit-testable on a host JVM against the NIST KAT. BouncyCastle builds the
 * private key directly from the portable **64-byte seed** (the canonical secret both
 * @noble and CryptoKit regenerate the keypair from).
 */
object MlKem768 {
    private val params = MLKEMParameters.ml_kem_768

    private fun privFromSeed(seed: ByteArray) = MLKEMPrivateKeyParameters(params, seed)
    private fun pub(ek: ByteArray) = MLKEMPublicKeyParameters(params, ek)

    /** The 1184-byte encapsulation key derived from a 64-byte seed. */
    fun ekFromSeed(seed: ByteArray): ByteArray = privFromSeed(seed).publicKeyParameters.encoded

    /** Random encapsulation to [ek] → (ciphertext 1088 B, shared secret 32 B). */
    fun encapsulate(ek: ByteArray, random: SecureRandom = SecureRandom()): Pair<ByteArray, ByteArray> {
        val e = MLKEMGenerator(random).generateEncapsulated(pub(ek))
        return e.encapsulation to e.secret
    }

    /** Deterministic encapsulation with an explicit 32-byte message [m] — for the KAT. */
    fun encapsulateDeterministic(ek: ByteArray, m: ByteArray): Pair<ByteArray, ByteArray> {
        val e = MLKEMGenerator.internalGenerateEncapsulated(pub(ek), m)
        return e.encapsulation to e.secret
    }

    /** Decapsulate [ct] with the key regenerated from [seed] → shared secret 32 B. */
    fun decapsulate(seed: ByteArray, ct: ByteArray): ByteArray =
        MLKEMExtractor(privFromSeed(seed)).extractSecret(ct)
}

/**
 * Post-quantum **hybrid KEM** for cross-user key wrapping (Store v3 §6.3),
 * byte-compatible with the web `resources/js/shared/pq-kem.js` and the iOS
 * `PQKEM.swift`:
 *   - PQ leg:  ML-KEM-768 (FIPS 203, via [MlKem768] / BouncyCastle → @noble / CryptoKit)
 *   - EC leg:  X25519 (libsodium scalarmult, RFC 7748)
 *   - combine: HKDF-SHA256(ss_ec ‖ ss_pq, salt=∅, info="ledgerline/kem/v1"‖ctx) → 32-byte wrap key
 *   - seal:    crypto_secretbox under the derived wrap key
 *
 * The ML-KEM secret is the portable 64-byte FIPS-203 seed (sealed under VK by the
 * caller). Confidentiality holds unless BOTH primitives fall (PQXDH-style). Fail-closed
 * on an unknown suite. ML-KEM interop is verified by `PQKEMKatTest`; the full hybrid
 * roundtrip (needs native libsodium) by the instrumented `PQKEMInstrumentedTest`.
 */
@Singleton
class PQKEM @Inject constructor() {
    // Low-level libsodium (loads the bundled native lib on construction; int return, 0 = ok).
    private val sodium = SodiumAndroid()
    private val json = Json { ignoreUnknownKeys = true }

    /** The wire envelope: all fields base64 (libsodium ORIGINAL / standard padded). */
    @Serializable
    data class Envelope(val suite: Int, val epk: String, val kem_ct: String, val c: String, val n: String) {
        fun toJson(): String = Json.encodeToString(serializer(), this)
    }

    /** ML-KEM-768 identity: `ek` = 1184-byte public encapsulation key (published);
     *  `seed` = 64-byte secret seed (sealed under VK by the caller). */
    data class Identity(val ek: ByteArray, val seed: ByteArray)

    /** A fresh ML-KEM-768 identity from a random 64-byte FIPS-203 seed. */
    fun mlkemKeypair(): Identity {
        val seed = ByteArray(64).also { SecureRandom().nextBytes(it) }
        return Identity(ek = MlKem768.ekFromSeed(seed), seed = seed)
    }

    /** A fresh X25519 keypair (the classical half of a user's sharing identity). */
    fun x25519Keypair(): Pair<ByteArray, ByteArray> {
        val pk = ByteArray(32)
        val sk = ByteArray(32)
        check(sodium.crypto_box_keypair(pk, sk) == 0) { "x25519 keygen failed" }
        return pk to sk
    }

    /** Hybrid-wrap [payload] to a recipient's public identity (X25519 pub + ML-KEM ek). */
    fun hybridWrap(
        payload: ByteArray,
        recipientX25519Pub: ByteArray,
        recipientMlkemEk: ByteArray,
        context: String = "",
    ): Envelope {
        val (kemCt, ssPq) = MlKem768.encapsulate(recipientMlkemEk)

        val ephPk = ByteArray(32)
        val ephSk = ByteArray(32)
        check(sodium.crypto_box_keypair(ephPk, ephSk) == 0) { "x25519 keygen failed" }
        val ssEc = scalarMult(ephSk, recipientX25519Pub)

        val wrapKey = deriveWrapKey(ssEc, ssPq, context)
        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val c = ByteArray(payload.size + 16)
        check(sodium.crypto_secretbox_easy(c, payload, payload.size.toLong(), nonce, wrapKey) == 0) { "secretbox seal failed" }

        return Envelope(suite = 1, epk = b64(ephPk), kem_ct = b64(kemCt), c = b64(c), n = b64(nonce))
    }

    /** Hybrid-unwrap [env] with the recipient's secret identity (X25519 sk + ML-KEM seed). */
    fun hybridUnwrap(env: Envelope, ownX25519Sk: ByteArray, ownMlkemSeed: ByteArray, context: String = ""): ByteArray? {
        if (env.suite != 1) return null // fail-closed on unknown suite
        val ssPq = MlKem768.decapsulate(ownMlkemSeed, unb64(env.kem_ct))
        val ssEc = scalarMult(ownX25519Sk, unb64(env.epk))
        val wrapKey = deriveWrapKey(ssEc, ssPq, context)
        val c = unb64(env.c)
        val out = ByteArray(c.size - 16)
        val ok = sodium.crypto_secretbox_open_easy(out, c, c.size.toLong(), unb64(env.n), wrapKey) == 0
        return if (ok) out else null
    }

    fun hybridUnwrap(envelopeJson: String, ownX25519Sk: ByteArray, ownMlkemSeed: ByteArray, context: String = ""): ByteArray? =
        hybridUnwrap(json.decodeFromString(Envelope.serializer(), envelopeJson), ownX25519Sk, ownMlkemSeed, context)

    private fun scalarMult(sk: ByteArray, pub: ByteArray): ByteArray {
        val q = ByteArray(32)
        check(sodium.crypto_scalarmult(q, sk, pub) == 0) { "x25519 scalarmult failed" }
        return q
    }

    /** HKDF-SHA256(ss_ec ‖ ss_pq, salt=∅, info = "ledgerline/kem/v1"‖context) → 32 bytes. */
    private fun deriveWrapKey(ssEc: ByteArray, ssPq: ByteArray, context: String): ByteArray {
        val ikm = ssEc + ssPq
        val info = (INFO_PREFIX + context).toByteArray(Charsets.UTF_8)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, ByteArray(0), info)) // empty salt → RFC-5869 default (zeros)
        val out = ByteArray(32)
        hkdf.generateBytes(out, 0, out.size)
        return out
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val INFO_PREFIX = "ledgerline/kem/v1"
    }
}
