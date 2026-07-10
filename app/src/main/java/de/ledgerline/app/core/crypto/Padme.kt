package de.ledgerline.app.core.crypto

import kotlin.math.floor
import kotlin.math.ln

/** Padmé bucket size (vault.js padmeSize): rounds n up to hide its exact length. */
fun padmeSize(n: Long): Long {
    if (n < 2) return n
    val e = floor(ln(n.toDouble()) / ln(2.0)).toInt()          // floor(log2 n)
    val s = floor(ln(e.toDouble()) / ln(2.0)).toInt() + 1       // floor(log2 e)+1
    val bits = e - s
    if (bits <= 0) return n
    val mask = (1L shl bits) - 1
    return (n + mask) and mask.inv()
}

/** Number of random padding bytes to append to a blob of the given size. */
fun padByteCount(size: Long): Long = padmeSize(size) - size
