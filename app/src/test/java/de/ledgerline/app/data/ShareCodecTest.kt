package de.ledgerline.app.data

import de.ledgerline.app.domain.model.ShareInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner-side `share` object round-trips through [FileRecordCodec] without data loss. */
class ShareCodecTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(s: String) = json.parseToJsonElement(s).jsonObject

    @Test fun unchanged_web_share_is_kept_verbatim_including_unknown_subkeys() {
        // A share authored by web carrying a field Android does not model.
        val raw = obj("""{"id":"f1","name":"a.txt","share":{"token":"t","sk":"k","futureField":"z"}}""")
        val decoded = FileRecordCodec.decodeFile(raw)
        // Decoding drops the unknown subkey from the typed model...
        assertEquals("t", decoded.share?.token)
        // ...but re-encoding keeps the raw share verbatim (unchanged), so nothing is lost.
        val out: JsonObject = FileRecordCodec.encodeFile(decoded, raw)
        assertEquals(raw["share"], out["share"])
        assertTrue(out["share"].toString().contains("futureField"))
    }

    @Test fun android_created_share_is_emitted_web_shaped() {
        val raw = obj("""{"id":"f1","name":"a.txt"}""")
        val f = FileRecordCodec.decodeFile(raw).copy(
            share = ShareInfo(token = "t2", sk = "k2", kind = "file", hasPassword = true),
        )
        val out = FileRecordCodec.encodeFile(f, raw).jsonObject["share"]!!.jsonObject
        assertEquals("t2", out["token"]?.toString()?.trim('"'))
        assertEquals("k2", out["sk"]?.toString()?.trim('"'))
        assertEquals("file", out["kind"]?.toString()?.trim('"'))
        assertEquals("true", out["hasPassword"]?.toString())
    }

    @Test fun cleared_share_is_removed() {
        val raw = obj("""{"id":"f1","name":"a.txt","share":{"token":"t","sk":"k"}}""")
        val f = FileRecordCodec.decodeFile(raw).copy(share = null)
        val out = FileRecordCodec.encodeFile(f, raw)
        assertNull(out["share"])
        assertFalse(out.containsKey("share"))
    }
}
