package de.ledgerline.app.core.crypto

import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crypto for **public share links**, byte-compatible with the web `ShareCrypto`
 * (`vault.js`). A share is protected by a fresh 32-byte **share key (SK)** that lives
 * ONLY in the link fragment (`#s:<sk>`) and is never sent to the server.
 *
 *  - The owner unwraps each per-file key under the Vault Key, then **re-wraps** it
 *    under SK: `wrap(rawFk, sk)` = secretbox `{"c","n"}` (random nonce).
 *  - The share manifest (album/folder metadata + the re-wrapped keys) is sealed under
 *    SK the same way — **no Padmé padding** (the plaintext is exactly `JSON.stringify`
 *    of the manifest, per the `share-manifest.json` fixture).
 *
 * Recipients derive nothing from their own vault; they only need SK from the URL.
 */
@Singleton
class ShareCrypto @Inject constructor(private val crypto: Crypto) {

    /** A fresh share key, base64 (32 random bytes). */
    fun newShareKey(): String {
        val sk = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return crypto.b64encode(sk)
    }

    /** Re-wrap a raw per-file key under [shareKeyB64] → `{"c","n"}` JSON. */
    fun wrapFileKey(rawFileKey: ByteArray, shareKeyB64: String): String =
        crypto.sealValue(rawFileKey, crypto.b64decode(shareKeyB64))

    /** Unwrap a `{"c","n"}` share-wrapped file key under [shareKeyB64]; null on failure. */
    fun unwrapFileKey(cn: String, shareKeyB64: String): ByteArray? =
        crypto.openValue(cn, crypto.b64decode(shareKeyB64))

    /** Seal a share manifest JSON under [shareKeyB64] → `{"c","n"}` (no padding). */
    fun sealManifest(json: String, shareKeyB64: String): String =
        crypto.sealValue(json.toByteArray(Charsets.UTF_8), crypto.b64decode(shareKeyB64))

    /** Open a share manifest under [shareKeyB64]; null on failure. */
    fun openManifest(cn: String, shareKeyB64: String): String? =
        crypto.openValue(cn, crypto.b64decode(shareKeyB64))?.let { String(it, Charsets.UTF_8) }
}
