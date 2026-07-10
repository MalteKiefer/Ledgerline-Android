package de.ledgerline.app.core.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BlobDiskCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache() = BlobDiskCache(File(tmp.root, "blobcache"))

    private val id = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun put_then_get_round_trips_bytes() {
        val c = cache()
        val bytes = byteArrayOf(1, 2, 3, 4, 5, -1, 0, 127)
        c.put(id, bytes)
        assertArrayEquals(bytes, c.get(id))
    }

    @Test
    fun has_reflects_presence() {
        val c = cache()
        assertFalse(c.has(id))
        c.put(id, byteArrayOf(9))
        assertTrue(c.has(id))
    }

    @Test
    fun absent_blob_returns_null() {
        assertNull(cache().get(id))
    }

    @Test
    fun remove_deletes_blob() {
        val c = cache()
        c.put(id, byteArrayOf(1, 2, 3))
        c.remove(id)
        assertNull(c.get(id))
        assertFalse(c.has(id))
    }

    @Test
    fun clear_empties_the_cache() {
        val c = cache()
        c.put(id, byteArrayOf(1))
        c.put("11111111-2222-3333-4444-555555555555", byteArrayOf(2, 3))
        c.clear()
        assertNull(c.get(id))
        assertEquals(0L, c.sizeBytes())
    }

    @Test
    fun sizeBytes_sums_file_sizes() {
        val c = cache()
        assertEquals(0L, c.sizeBytes())
        c.put(id, ByteArray(10))
        c.put("11111111-2222-3333-4444-555555555555", ByteArray(5))
        assertEquals(15L, c.sizeBytes())
        c.clear()
        assertEquals(0L, c.sizeBytes())
    }

    @Test
    fun put_overwrites_existing_blob() {
        val c = cache()
        c.put(id, byteArrayOf(1, 2, 3))
        c.put(id, byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), c.get(id))
    }

    @Test
    fun ids_with_slash_or_dotdot_are_rejected_safely() {
        val c = cache()
        // put is a no-op for unsafe ids; get/has treat them as absent — no throw.
        c.put("../escape", byteArrayOf(1, 2, 3))
        c.put("sub/dir", byteArrayOf(4, 5))
        c.put("", byteArrayOf(6))

        assertNull(c.get("../escape"))
        assertNull(c.get("sub/dir"))
        assertNull(c.get(""))
        assertFalse(c.has("../escape"))
        assertFalse(c.has("sub/dir"))
        // Nothing escaped the cache root.
        assertEquals(0L, c.sizeBytes())
        // remove of an unsafe id is a harmless no-op.
        c.remove("../escape")
    }
}
