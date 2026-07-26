package de.ledgerline.app.core

import java.security.SecureRandom

/**
 * Record-id generator, byte-shape-identical to the web/iOS `newId()`
 * (`resources/js/shared/sealed-store.js`): 16 CSPRNG bytes rendered as **32-char lowercase hex**,
 * no dashes. The sharded stores derive a record's bucket from `id.substring(0,8)`, and deep-links /
 * cross-client references assume this exact shape — a dashed `UUID` would violate the contract.
 */
object Ids {
    private val random = SecureRandom()

    fun newId(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return buildString(32) { b.forEach { append("%02x".format(it)) } }
    }
}
