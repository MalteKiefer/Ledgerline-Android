package de.ledgerline.app.core.passwords

/**
 * Coarse password score (0–4), matching the web `pwScore` (`passwords-util.js`): +1 length ≥8,
 * +1 length ≥12, +1 has both lower & upper, +1 has a digit, +1 has a symbol; capped at 4. The
 * health UI flags a password as **weak** when the score is `< 3`.
 */
object PasswordStrength {
    fun score(pw: String): Int {
        if (pw.isEmpty()) return 0
        var s = 0
        if (pw.length >= 8) s++
        if (pw.length >= 12) s++
        if (pw.any { it.isLowerCase() } && pw.any { it.isUpperCase() }) s++
        if (pw.any { it.isDigit() }) s++
        if (pw.any { !it.isLetterOrDigit() }) s++
        return minOf(s, 4)
    }

    fun isWeak(pw: String): Boolean = score(pw) < 3
}
