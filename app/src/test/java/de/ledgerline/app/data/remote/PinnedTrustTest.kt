package de.ledgerline.app.data.remote

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PinnedTrustTest {
    @Test fun spki_pin_is_stable_for_same_cert() {
        val cert = HeldCertificate.Builder().commonName("home.kiefer-networks.de").build().certificate
        val a = PinnedTrust.spkiSha256Base64(cert)
        val b = PinnedTrust.spkiSha256Base64(cert)
        assertNotNull(a)
        assertEquals(a, b)
        assert(a.startsWith("sha256/"))
    }
}
