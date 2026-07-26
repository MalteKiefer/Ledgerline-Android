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

    // A fake with a tunable limit so tests can drive put()'s auto-enforcement.
    private class LimitFlags(private val max: Long) : OfflineFlags {
        override fun enabled() = true
        override fun filesPolicy() = de.ledgerline.app.data.offline.FileBlobPolicy.ON_DEMAND
        override fun photosPolicy() = de.ledgerline.app.data.offline.PhotoBlobPolicy.ON_DEMAND
        override fun contactsPolicy() = de.ledgerline.app.data.offline.ContactBlobPolicy.ON_DEMAND
        override fun maxBytes() = max
        override fun wifiOnly() = false
        override fun chargingOnly() = false
    }

    private fun ids(n: Int): List<String> =
        (0 until n).map { "0000000%d-0000-0000-0000-000000000000".format(it) }

    @Test
    fun enforceLimit_evicts_oldest_first_until_under_limit() {
        val c = cache()
        val root = File(tmp.root, "blobcache")
        val list = ids(4)
        // Each blob is 10 bytes → 40 total. Give distinct, ascending lastModified so
        // list[0] is oldest and list[3] newest.
        val base = 1_000_000_000L
        list.forEachIndexed { i, id ->
            c.put(id, ByteArray(10))
            File(root, id).setLastModified(base + i * 1000L)
        }
        assertEquals(40L, c.sizeBytes())

        // Limit fits ~2 blobs (25 bytes) → must evict the two oldest.
        c.enforceLimit(25L)

        assertEquals(20L, c.sizeBytes())
        assertFalse(c.has(list[0]))
        assertFalse(c.has(list[1]))
        assertTrue(c.has(list[2]))
        assertTrue(c.has(list[3]))
    }

    @Test
    fun enforceLimit_unlimited_never_evicts() {
        val c = cache()
        val list = ids(3)
        list.forEach { c.put(it, ByteArray(10)) }
        assertEquals(30L, c.sizeBytes())

        c.enforceLimit(0L)

        assertEquals(30L, c.sizeBytes())
        list.forEach { assertTrue(c.has(it)) }
    }

    @Test
    fun touch_on_get_protects_recently_read_blob_from_eviction() {
        val c = cache()
        val root = File(tmp.root, "blobcache")
        val list = ids(3)
        val base = 1_000_000_000L
        list.forEachIndexed { i, id ->
            c.put(id, ByteArray(10))
            File(root, id).setLastModified(base + i * 1000L)
        }
        // list[0] is the oldest. Read it → get() touches it to now, so it's newest.
        c.get(list[0])

        // Add a fourth blob; enforce a limit fitting 3 (35 bytes) → evict one.
        val fourth = "0000000f-0000-0000-0000-000000000000"
        c.put(fourth, ByteArray(10))
        File(root, fourth).setLastModified(base + 5000L)
        c.enforceLimit(35L)

        // The touched blob survives; the now-oldest (list[1]) is evicted instead.
        assertTrue(c.has(list[0]))
        assertFalse(c.has(list[1]))
    }

    @Test
    fun put_auto_enforces_limit_keeping_size_under_max() {
        val root = File(tmp.root, "blobcache")
        val c = BlobDiskCache(root, LimitFlags(25L))
        val list = ids(4)
        // Each put auto-enforces the 25-byte limit; distinct lastModified so eviction
        // is deterministic (older writes evicted first).
        val base = 1_000_000_000L
        list.forEachIndexed { i, id ->
            c.put(id, ByteArray(10))
            File(root, id).setLastModified(base + i * 1000L)
        }
        // After all puts, cache stays under the limit.
        assertTrue("sizeBytes=${c.sizeBytes()}", c.sizeBytes() <= 25L)
    }

    @Test
    fun put_never_evicts_the_just_written_blob_even_below_one_blob_limit() {
        val root = File(tmp.root, "blobcache")
        // Limit smaller than a single blob (10 bytes) — the just-written blob must survive.
        val c = BlobDiskCache(root, LimitFlags(5L))
        c.put(id, ByteArray(10))
        assertTrue(c.has(id))
        assertArrayEquals(ByteArray(10), c.get(id))
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
