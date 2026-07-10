package de.ledgerline.app.ui.gallery

import de.ledgerline.app.domain.model.GalleryPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesRecomputeTest {

    private fun photo(id: String) = GalleryPhoto(id = id)

    @Test
    fun `removes trashed ids from groups`() {
        val groups = listOf(listOf(photo("a"), photo("b"), photo("c")))
        val out = recomputeAfterTrash(groups, setOf("b"))
        assertEquals(1, out.size)
        assertEquals(listOf("a", "c"), out.first().map { it.id })
    }

    @Test
    fun `drops a group that falls to a single member`() {
        val groups = listOf(listOf(photo("a"), photo("b")))
        val out = recomputeAfterTrash(groups, setOf("b"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `keeps a group that still has two or more members`() {
        val groups = listOf(listOf(photo("a"), photo("b"), photo("c")))
        val out = recomputeAfterTrash(groups, setOf("c"))
        assertEquals(listOf("a", "b"), out.single().map { it.id })
    }

    @Test
    fun `unaffected groups pass through unchanged`() {
        val groups = listOf(
            listOf(photo("a"), photo("b")),
            listOf(photo("x"), photo("y")),
        )
        val out = recomputeAfterTrash(groups, setOf("a"))
        assertEquals(1, out.size)
        assertEquals(listOf("x", "y"), out.single().map { it.id })
    }
}
