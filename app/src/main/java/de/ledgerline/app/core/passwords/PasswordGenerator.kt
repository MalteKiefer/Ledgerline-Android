package de.ledgerline.app.core.passwords

import java.security.SecureRandom

/** A configurable random password generator (CSPRNG-backed). */
object PasswordGenerator {
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?"

    fun generate(
        length: Int = 20,
        lower: Boolean = true,
        upper: Boolean = true,
        digits: Boolean = true,
        symbols: Boolean = true,
    ): String {
        val pool = buildString {
            if (lower) append(LOWER)
            if (upper) append(UPPER)
            if (digits) append(DIGITS)
            if (symbols) append(SYMBOLS)
        }.ifEmpty { LOWER + UPPER + DIGITS }
        val rnd = SecureRandom()
        return buildString { repeat(length.coerceIn(4, 128)) { append(pool[rnd.nextInt(pool.length)]) } }
    }
}
