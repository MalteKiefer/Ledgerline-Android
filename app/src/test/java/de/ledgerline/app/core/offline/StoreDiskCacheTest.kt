package de.ledgerline.app.core.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoreDiskCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache() = StoreDiskCache(File(tmp.root, "storecache"))

    @Test
    fun put_then_get_round_trips_ciphertext_and_version() {
        val c = cache()
        c.put("workspace", StoreEnvelope(ciphertext = "sealed-blob", version = 7))

        val got = c.get("workspace")
        assertEquals("sealed-blob", got?.ciphertext)
        assertEquals(7, got?.version)
    }

    @Test
    fun null_ciphertext_round_trips() {
        val c = cache()
        c.put("gallery", StoreEnvelope(ciphertext = null, version = 3))

        val got = c.get("gallery")
        assertNull(got?.ciphertext)
        assertEquals(3, got?.version)
    }

    @Test
    fun absent_key_returns_null() {
        assertNull(cache().get("nope"))
    }

    @Test
    fun remove_deletes_entry() {
        val c = cache()
        c.put("workspace", StoreEnvelope("x", 1))
        c.remove("workspace")
        assertNull(c.get("workspace"))
    }

    @Test
    fun clear_empties_the_cache() {
        val c = cache()
        c.put("workspace", StoreEnvelope("a", 1))
        c.put("gallery", StoreEnvelope("b", 2))
        c.clear()
        assertNull(c.get("workspace"))
        assertNull(c.get("gallery"))
        assertEquals(0L, c.sizeBytes())
    }

    @Test
    fun sizeBytes_is_positive_after_put_and_zero_after_clear() {
        val c = cache()
        assertEquals(0L, c.sizeBytes())
        c.put("workspace", StoreEnvelope("some-ciphertext", 1))
        assertTrue(c.sizeBytes() > 0L)
        c.clear()
        assertEquals(0L, c.sizeBytes())
    }

    @Test
    fun put_overwrites_existing_entry() {
        val c = cache()
        c.put("workspace", StoreEnvelope("old", 1))
        c.put("workspace", StoreEnvelope("new", 2))

        val got = c.get("workspace")
        assertEquals("new", got?.ciphertext)
        assertEquals(2, got?.version)
    }

    @Test
    fun corrupt_file_returns_null() {
        val root = File(tmp.root, "storecache").apply { mkdirs() }
        File(root, "workspace.json").writeText("}{ not json at all")

        assertNull(StoreDiskCache(root).get("workspace"))
    }
}
