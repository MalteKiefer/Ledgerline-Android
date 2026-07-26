package de.ledgerline.app.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Public-share crypto roundtrip on-device (needs native libsodium). The `ShareCrypto`
 * format is the same SK-keyed secretbox `{"c","n"}` the web/iOS clients use — verified
 * with the `share-manifest.json` fixture's fixed 32-byte share key (`sk = 0x00..0x1f`).
 * The manifest's own byte-for-byte JSON pinning is a manifest-builder concern that
 * lands with the share UI (§ S4 REST wiring).
 */
@RunWith(AndroidJUnit4::class)
class ShareCryptoInstrumentedTest {
    private val crypto = SodiumCrypto()
    private val sc = ShareCrypto(crypto)

    // Fixture SK: base64 of 0x00..0x1f.
    private val fixtureSk = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="

    @Test fun manifest_wrap_unwrap_roundtrips_under_fixture_sk() {
        val json = """{"name":"Trip 2024","allowDownload":false,"photos":[{"id":"p1","t":"image"}]}"""
        val sealed = sc.sealManifest(json, fixtureSk)
        assertEquals(json, sc.openManifest(sealed, fixtureSk))
        // Wrong key → fail-closed.
        assertNull(sc.openManifest(sealed, sc.newShareKey()))
    }

    @Test fun file_key_rewrap_roundtrips() {
        val rawFk = ByteArray(32) { (it * 7).toByte() }
        val wrapped = sc.wrapFileKey(rawFk, fixtureSk)
        assertArrayEquals(rawFk, sc.unwrapFileKey(wrapped, fixtureSk))
    }

    @Test fun new_share_key_is_32_bytes() {
        assertEquals(32, crypto.b64decode(sc.newShareKey()).size)
    }

    @Test fun share_wrapped_value_is_generic_cn_format() {
        // A ShareCrypto seal opens with the generic {c,n} opener under the same SK bytes.
        val skBytes = crypto.b64decode(fixtureSk)
        val sealed = sc.sealManifest("hello", fixtureSk)
        assertEquals("hello", String(crypto.openValue(sealed, skBytes)!!, Charsets.UTF_8))
    }
}
