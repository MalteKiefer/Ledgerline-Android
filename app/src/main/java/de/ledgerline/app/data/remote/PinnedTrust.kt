package de.ledgerline.app.data.remote

import okhttp3.CertificatePinner
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * Trust-on-first-use SPKI pinning. At pairing we record the leaf certificate's
 * SubjectPublicKeyInfo SHA-256 ("sha256/...."). Later requests enforce it via an
 * OkHttp CertificatePinner, so a swapped server certificate (even one from a
 * valid CA) is rejected.
 */
object PinnedTrust {
    fun spkiSha256Base64(cert: X509Certificate): String {
        val spki = cert.publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(spki)
        return "sha256/" + java.util.Base64.getEncoder().encodeToString(digest)
    }

    fun pinnerFor(host: String, pin: String): CertificatePinner =
        CertificatePinner.Builder().add(host, pin).build()
}
