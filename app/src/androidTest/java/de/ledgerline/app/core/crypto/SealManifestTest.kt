package de.ledgerline.app.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SealManifestTest {
    private val crypto = SodiumCrypto()

    @Test fun seal_then_open_roundtrips_with_padding() {
        val vk = ByteArray(32) { (it + 5).toByte() }
        val json = """{"v":1,"files":[{"id":"f1","name":"a.txt"}]}"""
        val sealed = crypto.sealManifest(json, vk)
        val opened = crypto.openManifest(sealed, vk)!!
        // openManifest returns the padded plaintext; the JSON prefix must match.
        assertTrue(opened.startsWith(json))
        assertTrue("padded to a 4-KiB bucket", opened.length % 4096 == 0)
        // The JSON is still parseable after trimming trailing whitespace.
        assertEquals('}', opened.trimEnd().last())
    }
}
