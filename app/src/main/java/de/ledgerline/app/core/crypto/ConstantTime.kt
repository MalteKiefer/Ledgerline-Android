package de.ledgerline.app.core.crypto

/**
 * Constant-time equality for secret comparisons (e.g. TOFU fingerprints, MACs),
 * so a comparison's timing cannot leak how many leading bytes matched. Mirrors the
 * iOS `ConstantTime.equal`.
 *
 * The lengths are compared first: for the byte strings we compare here (fixed-size
 * fingerprints/keys) the length is not secret.
 */
object ConstantTime {
    fun equal(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
