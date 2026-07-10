package de.ledgerline.app.core.crypto

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretBox
import com.sun.jna.NativeLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    private val ls = LazySodiumAndroid(SodiumAndroid())

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

    override fun openManifest(ciphertext: String, vk: ByteArray): String? {
        return try {
            val env = lenientJson.parseToJsonElement(ciphertext) as JsonObject
            val c = env["c"]!!.jsonPrimitive.content
            val n = env["n"]!!.jsonPrimitive.content
            val plain = secretBoxOpen(b64decode(c), b64decode(n), vk) ?: return null
            String(plain, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override fun sealManifest(json: String, vk: ByteArray): String {
        val bucket = 4096
        val target = ((json.length + 1 + bucket - 1) / bucket) * bucket   // ceil((len+1)/4096)*4096
        val padded = json + " ".repeat(target - json.length)
        val plain = padded.toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(SecretBox.NONCEBYTES)                       // 24
        randomBytes(nonce)
        val cipher = ByteArray(plain.size + SecretBox.MACBYTES)
        check(ls.cryptoSecretBoxEasy(cipher, plain, plain.size.toLong(), nonce, vk)) { "seal failed" }
        return """{"c":"${b64encode(cipher)}","n":"${b64encode(nonce)}"}"""
    }

    private fun randomBytes(out: ByteArray) {
        java.security.SecureRandom().nextBytes(out)
    }

    /** Test-only helper to build a secretbox ciphertext fixture. */
    internal fun secretBoxSealForTest(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(message.size + SecretBox.MACBYTES)
        check(ls.cryptoSecretBoxEasy(out, message, message.size.toLong(), nonce, key)) { "seal failed" }
        return out
    }
}
