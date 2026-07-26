package de.ledgerline.app.core.passwords

import java.security.MessageDigest

/**
 * HIBP k-anonymity breach check, matching the web/iOS clients. The full SHA-1 never leaves the
 * device: the client sends the 5-hex **prefix** to `GET /passwords/breach?prefix=`, the server
 * forwards the HIBP range (`SUFFIX:COUNT` lines, 35-hex suffix), and the client matches its own
 * 35-char suffix locally. Hex is uppercase throughout.
 */
object BreachCheck {
    /** Uppercase 40-hex SHA-1 of the UTF-8 password. */
    fun sha1Hex(password: String): String =
        MessageDigest.getInstance("SHA-1").digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    fun prefix(hex: String): String = hex.substring(0, 5)
    fun suffix(hex: String): String = hex.substring(5)

    /** The breach count for [suffix] in the server's HIBP [rangeBody], or 0 if not present. */
    fun countInRange(rangeBody: String, suffix: String): Int {
        val target = suffix.uppercase()
        rangeBody.lineSequence().forEach { line ->
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().uppercase() == target) {
                return line.substring(i + 1).trim().toIntOrNull() ?: 0
            }
        }
        return 0
    }
}
