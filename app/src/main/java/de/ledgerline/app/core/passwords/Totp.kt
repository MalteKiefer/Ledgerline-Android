package de.ledgerline.app.core.passwords

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC-6238 TOTP, defaulting to **SHA-1 / 6 digits / 30 s** — the parameters the web client
 * writes (it stores only the bare base32 secret at `fields.totp`, so those defaults are the
 * byte-compatible contract). Base32 decode is RFC 4648.
 */
object Totp {
    private const val PERIOD = 30
    private const val DIGITS = 6

    /**
     * Normalise a user-entered TOTP value to the bare base32 secret we store, matching the web
     * `totpSecret()` (`passwords-util.js`): a pasted `otpauth://…?secret=XXX` URI yields its
     * `secret` query param; a plain base32 secret is returned trimmed. This keeps the stored value
     * cross-client-identical and lets `code()` (which expects bare base32) produce live digits.
     */
    fun normalizeSecret(value: String): String {
        val v = value.trim()
        if (v.isEmpty()) return ""
        if (!v.startsWith("otpauth://", ignoreCase = true)) return v
        val m = Regex("[?&]secret=([^&]+)", RegexOption.IGNORE_CASE).find(v) ?: return ""
        return runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(m.groupValues[1])
    }

    /** The current 6-digit code for [base32Secret], or null if the secret is not valid base32. */
    fun code(base32Secret: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String? {
        val key = base32Decode(base32Secret) ?: return null
        if (key.isEmpty()) return null
        val counter = nowSeconds / PERIOD
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) { msg[i] = (c and 0xff).toByte(); c = c shr 8 }
        val hmac = try {
            Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(msg)
        } catch (_: Exception) {
            return null
        }
        val offset = hmac[hmac.size - 1].toInt() and 0x0f
        val bin = ((hmac[offset].toInt() and 0x7f) shl 24) or
            ((hmac[offset + 1].toInt() and 0xff) shl 16) or
            ((hmac[offset + 2].toInt() and 0xff) shl 8) or
            (hmac[offset + 3].toInt() and 0xff)
        val otp = bin % 1_000_000
        return otp.toString().padStart(DIGITS, '0')
    }

    /** Seconds until the current code rolls over (for the countdown ring). */
    fun secondsRemaining(nowSeconds: Long = System.currentTimeMillis() / 1000): Int =
        (PERIOD - (nowSeconds % PERIOD)).toInt()

    /** RFC-4648 base32 decode (upper/lowercase, spaces + `=` padding tolerated). Null on invalid. */
    fun base32Decode(input: String): ByteArray? {
        val clean = input.trim().replace(" ", "").replace("-", "").trimEnd('=').uppercase()
        if (clean.isEmpty()) return ByteArray(0)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = alphabet.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
