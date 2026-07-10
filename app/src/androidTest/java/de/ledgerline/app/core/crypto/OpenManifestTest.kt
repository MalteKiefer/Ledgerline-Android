package de.ledgerline.app.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenManifestTest {
    private val crypto = SodiumCrypto()

    @Test fun openManifest_recovers_padded_json_and_rejects_wrong_key() {
        val vk = ByteArray(32) { (it * 3).toByte() }
        val nonce = ByteArray(24) { (it + 1).toByte() }
        val json = """{"v":1,"notes":[]}"""
        val padded = json + " ".repeat(4096 - json.length)
        val cipher = crypto.secretBoxSealForTest(padded.toByteArray(), nonce, vk)
        val sealed = """{"c":"${crypto.b64encode(cipher)}","n":"${crypto.b64encode(nonce)}"}"""

        val out = crypto.openManifest(sealed, vk)
        assertTrue(out!!.startsWith("""{"v":1,"notes":[]}"""))
        assertEquals(padded, out)

        val wrong = vk.copyOf().also { it[0]++ }
        assertNull(crypto.openManifest(sealed, wrong))
    }
}
