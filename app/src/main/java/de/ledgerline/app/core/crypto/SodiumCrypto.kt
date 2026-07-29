package de.ledgerline.app.core.crypto

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretBox
import com.goterl.lazysodium.interfaces.SecretStream
import com.sun.jna.NativeLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * libsodium-backed [Crypto] implementation, byte-compatible with
 * resources/js/vault.js. Semantics: Argon2id (ALG_ARGON2ID13) to 32 bytes,
 * XSalsa20-Poly1305 secretbox with a 24-byte nonce, keyless BLAKE2b to 32
 * bytes, and standard padded Base64 (libsodium ORIGINAL == Base64.NO_WRAP).
 */
@Singleton
class SodiumCrypto @Inject constructor() : Crypto {
    private val sodium = SodiumAndroid()
    private val ls = LazySodiumAndroid(sodium)

    override fun deriveKek(passphrase: ByteArray, salt: ByteArray, opsLimit: Long, memLimit: Long): ByteArray {
        require(salt.size == PwHash.SALTBYTES) { "salt must be ${PwHash.SALTBYTES} bytes" }
        val out = ByteArray(SecretBox.KEYBYTES) // 32
        val ok = ls.cryptoPwHash(
            out, out.size,
            passphrase, passphrase.size,
            salt,
            opsLimit, NativeLong(memLimit),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        check(ok) { "argon2id derivation failed" }
        return out
    }

    override fun secretBoxOpen(cipher: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        val msgLen = cipher.size - SecretBox.MACBYTES
        if (msgLen < 0) return null
        val out = ByteArray(msgLen)
        val ok = ls.cryptoSecretBoxOpenEasy(out, cipher, cipher.size.toLong(), nonce, key)
        return if (ok) out else null
    }

    override fun genericHash32(input: ByteArray): ByteArray {
        val out = ByteArray(32)
        val ok = ls.cryptoGenericHash(out, out.size, input, input.size.toLong())
        check(ok) { "generichash failed" }
        return out
    }

    override fun b64decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    override fun b64encode(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    override fun fromHex(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() }
        return ByteArray(clean.length / 2) {
            ((clean[it * 2].digitToInt(16) shl 4) or clean[it * 2 + 1].digitToInt(16)).toByte()
        }
    }

    private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Store-v3 crypto-suite id; every sealed manifest carries it, an unknown suite fails closed. */
    private val SUITE = 1

    override fun openManifest(ciphertext: String, vk: ByteArray): String? {
        return try {
            val env = lenientJson.parseToJsonElement(ciphertext) as JsonObject
            if (unknownSuite(env)) return null // §5 fail-closed: never guess an unknown crypto stack
            val c = (env["c"] ?: return null).jsonPrimitive.content
            val n = (env["n"] ?: return null).jsonPrimitive.content
            val plain = secretBoxOpen(b64decode(c), b64decode(n), vk) ?: return null
            String(plain, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Seal a manifest byte-exact to the web (`vault.js` `sealManifest`): canonical-JSON payload,
     * space-padded to a `max(4096, padme(len+1))` bucket (metadata-size hiding), secretbox-sealed
     * under VK, wrapped in the suite-tagged `{suite:1,c,n}` envelope. The incoming [json] is
     * re-serialised through [CanonicalJson] so every module (not just files/gallery) is canonical.
     */
    override fun sealManifest(json: String, vk: ByteArray): String {
        val canonical = CanonicalJson.encode(lenientJson.parseToJsonElement(json))
        val target = maxOf(4096L, padmeSize((canonical.length + 1).toLong())).toInt()
        val padded = canonical + " ".repeat(target - canonical.length)
        val plain = padded.toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(SecretBox.NONCEBYTES)                       // 24
        randomBytes(nonce)
        val cipher = ByteArray(plain.size + SecretBox.MACBYTES)
        check(ls.cryptoSecretBoxEasy(cipher, plain, plain.size.toLong(), nonce, vk)) { "seal failed" }
        return """{"suite":$SUITE,"c":"${b64encode(cipher)}","n":"${b64encode(nonce)}"}"""
    }

    /** True when the envelope carries a `suite` that is present and ≠ the known [SUITE]. */
    private fun unknownSuite(env: JsonObject): Boolean {
        val s = (env["suite"] as? JsonPrimitive)?.intOrNull ?: return false // absent → legacy-ok
        return s != SUITE
    }


    override fun sealValue(data: ByteArray, key: ByteArray): String {
        val nonce = ByteArray(SecretBox.NONCEBYTES)
        randomBytes(nonce)
        val cipher = ByteArray(data.size + SecretBox.MACBYTES)
        check(ls.cryptoSecretBoxEasy(cipher, data, data.size.toLong(), nonce, key)) { "seal failed" }
        return """{"c":"${b64encode(cipher)}","n":"${b64encode(nonce)}"}"""
    }

    override fun openValue(cn: String, key: ByteArray): ByteArray? = try {
        val env = lenientJson.parseToJsonElement(cn) as JsonObject
        if (unknownSuite(env)) null
        else {
            val c = (env["c"] ?: return null).jsonPrimitive.content
            val n = (env["n"] ?: return null).jsonPrimitive.content
            secretBoxOpen(b64decode(c), b64decode(n), key)
        }
    } catch (_: Exception) {
        null
    }

    override fun genericHash(input: ByteArray, outLen: Int): ByteArray {
        val out = ByteArray(outLen)
        check(ls.cryptoGenericHash(out, out.size, input, input.size.toLong())) { "generichash failed" }
        return out
    }

    /**
     * Constant-time equality via libsodium `sodium_memcmp` (vetted primitive; §28). Lengths
     * are compared first (not secret for the fixed-size fingerprints/MACs we compare); a
     * length mismatch returns false without calling into native code. `sodium_memcmp` returns
     * 0 iff the [len]-byte prefixes are equal, in constant time.
     */
    override fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        return sodium.sodium_memcmp(a, b, a.size) == 0
    }

    /** Test-only: secretbox-seal [plaintext] with an explicit [nonce] (byte-parity fixtures). */
    fun secretBoxSealForTest(plaintext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val cipher = ByteArray(plaintext.size + SecretBox.MACBYTES)
        check(ls.cryptoSecretBoxEasy(cipher, plaintext, plaintext.size.toLong(), nonce, key)) { "seal failed" }
        return cipher
    }

    private fun randomBytes(out: ByteArray) {
        java.security.SecureRandom().nextBytes(out)
    }

    override val contentChunkSize: Int = 4 * 1024 * 1024

    override fun u32le(n: Int): ByteArray = byteArrayOf(
        (n and 0xff).toByte(),
        ((n ushr 8) and 0xff).toByte(),
        ((n ushr 16) and 0xff).toByte(),
        ((n ushr 24) and 0xff).toByte(),
    )

    override fun readU32le(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xff) or
            ((bytes[off + 1].toInt() and 0xff) shl 8) or
            ((bytes[off + 2].toInt() and 0xff) shl 16) or
            ((bytes[off + 3].toInt() and 0xff) shl 24)

    override fun newContentEncryptor(vk: ByteArray): Crypto.ContentEncryptor {
        val fk = ByteArray(SecretStream.KEYBYTES) // 32
        ls.cryptoSecretStreamKeygen(fk)
        val state = SecretStream.State.ByReference()
        val header = ByteArray(SecretStream.HEADERBYTES) // 24
        check(ls.cryptoSecretStreamInitPush(state, header, fk)) { "secretstream init_push failed" }
        return object : Crypto.ContentEncryptor {
            override val header: ByteArray = header

            override fun encryptChunk(chunk: ByteArray, isLast: Boolean): ByteArray {
                val cipher = ByteArray(chunk.size + SecretStream.ABYTES) // +17
                val tag = if (isLast) SecretStream.TAG_FINAL else SecretStream.TAG_MESSAGE
                check(
                    ls.cryptoSecretStreamPush(state, cipher, chunk, chunk.size.toLong(), tag),
                ) { "secretstream push failed" }
                return u32le(cipher.size) + cipher
            }

            override fun sealKey(): String {
                val nonce = ByteArray(SecretBox.NONCEBYTES) // 24
                randomBytes(nonce)
                val wrapped = ByteArray(fk.size + SecretBox.MACBYTES)
                check(
                    ls.cryptoSecretBoxEasy(wrapped, fk, fk.size.toLong(), nonce, vk),
                ) { "file key wrap failed" }
                return """{"c":"${b64encode(wrapped)}","n":"${b64encode(nonce)}"}"""
            }
        }
    }

    override fun contentDecryptor(encFileKey: String, vk: ByteArray): Crypto.ContentDecryptor {
        val env = lenientJson.parseToJsonElement(encFileKey) as JsonObject
        val c = (env["c"] ?: error("encFileKey missing 'c'")).jsonPrimitive.content
        val n = (env["n"] ?: error("encFileKey missing 'n'")).jsonPrimitive.content
        val fk = secretBoxOpen(b64decode(c), b64decode(n), vk) ?: error("file key unwrap failed")
        return decryptorFor(fk)
    }

    override fun contentDecryptorFromKey(fileKey: ByteArray): Crypto.ContentDecryptor = decryptorFor(fileKey)

    private fun decryptorFor(fk: ByteArray): Crypto.ContentDecryptor {
        val state = SecretStream.State.ByReference()
        return object : Crypto.ContentDecryptor {
            override val headerBytes: Int = SecretStream.HEADERBYTES // 24

            override fun start(header: ByteArray) {
                check(ls.cryptoSecretStreamInitPull(state, header, fk)) { "secretstream init_pull failed" }
            }

            override fun decryptFrame(frame: ByteArray): Pair<ByteArray, Boolean> {
                val message = ByteArray(frame.size - SecretStream.ABYTES)
                val tag = ByteArray(1)
                check(
                    ls.cryptoSecretStreamPull(state, message, tag, frame, frame.size.toLong()),
                ) { "secretstream pull failed" }
                return message to (tag[0] == SecretStream.TAG_FINAL)
            }
        }
    }
}
