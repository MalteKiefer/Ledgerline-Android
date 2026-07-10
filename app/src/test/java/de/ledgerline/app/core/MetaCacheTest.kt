package de.ledgerline.app.core

import de.ledgerline.app.domain.model.PhotoMetaBlob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaCacheTest {

    @Test
    fun put_get_has_return_stored_meta() {
        val cache = MetaCache()
        val meta = PhotoMetaBlob(embedding = listOf(0.1, 0.2))

        cache.put("a", meta)

        assertTrue(cache.has("a"))
        assertEquals(meta, cache.get("a"))
    }

    @Test
    fun absent_id_is_null_and_not_present() {
        val cache = MetaCache()

        assertNull(cache.get("missing"))
        assertFalse(cache.has("missing"))
    }

    @Test
    fun null_meta_is_still_present_but_get_returns_null() {
        val cache = MetaCache()

        cache.put("b", null)

        assertTrue(cache.has("b"))
        assertNull(cache.get("b"))
    }

    @Test
    fun clear_empties_the_cache() {
        val cache = MetaCache()
        cache.put("a", PhotoMetaBlob())
        cache.put("b", null)

        cache.clear()

        assertFalse(cache.has("a"))
        assertFalse(cache.has("b"))
        assertNull(cache.get("a"))
    }
}
