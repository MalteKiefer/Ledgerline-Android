package de.ledgerline.app.domain.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSearchTest {

    private val delta = 1e-9

    // --- cosine ---

    @Test
    fun cosine_of_identical_unit_vectors_is_one() {
        val v = SemanticSearch.norm(listOf(3.0, 4.0))
        assertEquals(1.0, SemanticSearch.cosine(v, v), delta)
    }

    @Test
    fun cosine_of_orthogonal_unit_vectors_is_zero() {
        val a = SemanticSearch.norm(listOf(1.0, 0.0))
        val b = SemanticSearch.norm(listOf(0.0, 1.0))
        assertEquals(0.0, SemanticSearch.cosine(a, b), delta)
    }

    @Test
    fun cosine_uses_min_length() {
        // Trailing dim on the longer vector is ignored.
        assertEquals(5.0, SemanticSearch.cosine(listOf(1.0, 2.0), listOf(1.0, 2.0, 100.0)), delta)
    }

    // --- norm ---

    @Test
    fun norm_produces_unit_length() {
        val n = SemanticSearch.norm(listOf(3.0, 4.0))
        assertEquals(0.6, n[0], delta)
        assertEquals(0.8, n[1], delta)
    }

    @Test
    fun norm_of_zero_vector_returns_zeros_not_nan() {
        val n = SemanticSearch.norm(listOf(0.0, 0.0))
        assertEquals(listOf(0.0, 0.0), n)
    }

    // --- rank ---

    @Test
    fun rank_orders_by_cosine_descending() {
        val q = SemanticSearch.norm(listOf(1.0, 0.0))
        val items = listOf(
            "far" to SemanticSearch.norm(listOf(0.0, 1.0)),        // cosine 0, dropped by threshold
            "near" to SemanticSearch.norm(listOf(1.0, 0.05)),      // cosine ~0.998
            "mid" to SemanticSearch.norm(listOf(1.0, 1.0)),        // cosine ~0.707
        )
        val ranked = SemanticSearch.rank(q, items, threshold = 0.2)
        assertEquals(listOf("near", "mid"), ranked)
    }

    @Test
    fun rank_drops_scores_at_or_below_threshold() {
        val q = SemanticSearch.norm(listOf(1.0, 0.0))
        // Exactly on the threshold must be dropped (strict `> threshold`, matching web `s > 0.2`).
        val onThreshold = listOf(0.2, 0.0) // cosine with q == 0.2 (q is unit, item unnormalised → dot=0.2)
        val ranked = SemanticSearch.rank(q, listOf("edge" to onThreshold), threshold = 0.2)
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun rank_drops_null_and_empty_embeddings() {
        val q = SemanticSearch.norm(listOf(1.0, 0.0))
        val items = listOf(
            "noEmb" to null,
            "emptyEmb" to emptyList<Double>(),
            "good" to SemanticSearch.norm(listOf(1.0, 0.0)),
        )
        assertEquals(listOf("good"), SemanticSearch.rank(q, items, threshold = 0.2))
    }

    @Test
    fun rank_with_empty_query_returns_empty() {
        assertTrue(
            SemanticSearch.rank(emptyList(), listOf("a" to listOf(1.0, 0.0)), threshold = 0.2).isEmpty()
        )
    }

    @Test
    fun rank_with_no_items_returns_empty() {
        val q = SemanticSearch.norm(listOf(1.0, 0.0))
        assertTrue(SemanticSearch.rank(q, emptyList(), threshold = 0.2).isEmpty())
    }

    @Test
    fun rank_caps_at_limit() {
        val q = SemanticSearch.norm(listOf(1.0, 0.0))
        val items = (1..10).map { "p$it" to SemanticSearch.norm(listOf(1.0, 0.0)) }
        val ranked = SemanticSearch.rank(q, items, threshold = 0.2, limit = 3)
        assertEquals(3, ranked.size)
    }

    @Test
    fun currentModel_picks_the_modal_nonnull_model() {
        val models = listOf("ViT-B-32", "ViT-B-32", "old-model", null, "ViT-B-32")
        assertEquals("ViT-B-32", SemanticSearch.currentModel(models))
    }

    @Test
    fun currentModel_is_null_when_no_model_tagged() {
        assertEquals(null, SemanticSearch.currentModel(listOf(null, null)))
        assertEquals(null, SemanticSearch.currentModel(emptyList()))
    }
}
