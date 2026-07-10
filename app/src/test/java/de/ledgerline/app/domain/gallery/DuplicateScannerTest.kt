package de.ledgerline.app.domain.gallery

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateScannerTest {

    private val delta = 1e-9

    // Unit 2-D vectors on the unit circle at angle theta, so cosine == dot.
    private fun unit(theta: Double): List<Double> = DuplicateScanner.norm(listOf(kotlin.math.cos(theta), kotlin.math.sin(theta)))

    // --- 1. dot ---

    @Test
    fun dot_same_vector() {
        assertEquals(1.0, DuplicateScanner.dot(listOf(1.0, 0.0), listOf(1.0, 0.0)), delta)
    }

    @Test
    fun dot_uses_min_length() {
        // Only first 2 dims compared; trailing 100.0 ignored.
        assertEquals(5.0, DuplicateScanner.dot(listOf(1.0, 2.0), listOf(1.0, 2.0, 100.0)), delta)
    }

    // --- 2. norm ---

    @Test
    fun norm_scales_to_unit_length() {
        val n = DuplicateScanner.norm(listOf(3.0, 4.0))
        assertEquals(0.6, n[0], delta)
        assertEquals(0.8, n[1], delta)
    }

    @Test
    fun norm_zero_vector_stays_zero() {
        val n = DuplicateScanner.norm(listOf(0.0, 0.0))
        assertEquals(2, n.size)
        assertEquals(0.0, n[0], delta)
        assertEquals(0.0, n[1], delta)
    }

    // --- 3. hamming ---

    @Test
    fun hamming_zero_is_zero() {
        assertEquals(0, DuplicateScanner.hamming(0L, 0L))
    }

    @Test
    fun hamming_counts_differing_bits() {
        assertEquals(2, DuplicateScanner.hamming(0b111L, 0b010L))
    }

    @Test
    fun hamming_full_64_bit_case() {
        // High bit set on one side only -> 1 differing bit (sign-agnostic).
        assertEquals(1, DuplicateScanner.hamming(0L, Long.MIN_VALUE))
        // All 64 bits differ.
        assertEquals(64, DuplicateScanner.hamming(0L, -1L))
    }

    // --- 4. Near-identical embeddings (dot >= 0.97, phash null) group ---

    @Test
    fun near_identical_embeddings_group() = runTest {
        // Two unit vectors ~9 degrees apart: cos(9deg) ~= 0.9877 >= 0.97.
        val a = unit(0.0)
        val b = unit(Math.toRadians(9.0))
        assertTrue("precondition: dot >= 0.97", DuplicateScanner.dot(a, b) >= 0.97)
        val items = listOf(
            DuplicateScanner.DupItem("A", a, null, isVideo = false),
            DuplicateScanner.DupItem("B", b, null, isVideo = false),
        )
        val groups = DuplicateScanner.groups(items) { _, _ -> }
        assertEquals(1, groups.size)
        assertEquals(listOf("A", "B"), groups[0])
    }

    @Test
    fun embeddings_below_threshold_do_not_group() = runTest {
        // ~30 degrees apart: cos ~= 0.866 < 0.97.
        val a = unit(0.0)
        val b = unit(Math.toRadians(30.0))
        assertTrue("precondition: dot < 0.97", DuplicateScanner.dot(a, b) < 0.97)
        val items = listOf(
            DuplicateScanner.DupItem("A", a, null, isVideo = false),
            DuplicateScanner.DupItem("B", b, null, isVideo = false),
        )
        assertTrue(DuplicateScanner.groups(items) { _, _ -> }.isEmpty())
    }

    // --- 5. Images by phash Hamming: <=3 groups, 4 does not ---

    @Test
    fun images_phash_hamming_three_group() = runTest {
        // 3 differing bits.
        val items = listOf(
            DuplicateScanner.DupItem("A", null, 0L, isVideo = false),
            DuplicateScanner.DupItem("B", null, 0b111L, isVideo = false),
        )
        val groups = DuplicateScanner.groups(items) { _, _ -> }
        assertEquals(1, groups.size)
        assertEquals(listOf("A", "B"), groups[0])
    }

    @Test
    fun images_phash_hamming_four_do_not_group() = runTest {
        // 4 differing bits -> exceeds image threshold of 3.
        val items = listOf(
            DuplicateScanner.DupItem("A", null, 0L, isVideo = false),
            DuplicateScanner.DupItem("B", null, 0b1111L, isVideo = false),
        )
        assertTrue(DuplicateScanner.groups(items) { _, _ -> }.isEmpty())
    }

    // --- 6. Video pair: Hamming 4 groups, 5 does not ---

    @Test
    fun video_phash_hamming_four_group() = runTest {
        val items = listOf(
            DuplicateScanner.DupItem("A", null, 0L, isVideo = true),
            DuplicateScanner.DupItem("B", null, 0b1111L, isVideo = false),
        )
        val groups = DuplicateScanner.groups(items) { _, _ -> }
        assertEquals(1, groups.size)
        assertEquals(listOf("A", "B"), groups[0])
    }

    @Test
    fun video_phash_hamming_five_do_not_group() = runTest {
        val items = listOf(
            DuplicateScanner.DupItem("A", null, 0L, isVideo = true),
            DuplicateScanner.DupItem("B", null, 0b11111L, isVideo = false),
        )
        assertTrue(DuplicateScanner.groups(items) { _, _ -> }.isEmpty())
    }

    // --- 7. Transitive union: A~B by embedding, B~C by phash -> {A,B,C} ---

    @Test
    fun transitive_union_across_embedding_and_phash() = runTest {
        val a = unit(0.0)
        val b = unit(Math.toRadians(9.0)) // A~B via embedding
        val items = listOf(
            DuplicateScanner.DupItem("A", a, null, isVideo = false),
            DuplicateScanner.DupItem("B", b, 0L, isVideo = false),
            DuplicateScanner.DupItem("C", null, 0b111L, isVideo = false), // B~C via phash (hd=3)
        )
        val groups = DuplicateScanner.groups(items) { _, _ -> }
        assertEquals(1, groups.size)
        assertEquals(listOf("A", "B", "C"), groups[0])
    }

    // --- 8. Lone item / no matches vs a single matched pair ---

    @Test
    fun lone_item_yields_empty() = runTest {
        val items = listOf(DuplicateScanner.DupItem("A", unit(0.0), 0L, isVideo = false))
        assertTrue(DuplicateScanner.groups(items) { _, _ -> }.isEmpty())
    }

    @Test
    fun no_matches_yields_empty() = runTest {
        val items = listOf(
            DuplicateScanner.DupItem("A", unit(0.0), 0L, isVideo = false),
            DuplicateScanner.DupItem("B", unit(Math.toRadians(90.0)), 0b1111L, isVideo = false),
        )
        assertTrue(DuplicateScanner.groups(items) { _, _ -> }.isEmpty())
    }

    @Test
    fun single_matched_pair_is_one_group_of_two() = runTest {
        val items = listOf(
            DuplicateScanner.DupItem("A", null, 0L, isVideo = false),
            DuplicateScanner.DupItem("B", null, 0b1L, isVideo = false),
        )
        val groups = DuplicateScanner.groups(items) { _, _ -> }
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
    }

    // --- phash null -> Hamming treated as 64 (no phash match) ---

    @Test
    fun phash_null_pair_only_groups_via_embedding() = runTest {
        // Both phash null: no phash path; only embedding can group.
        val a = unit(0.0)
        val b = unit(Math.toRadians(9.0))
        val items = listOf(
            DuplicateScanner.DupItem("A", a, null, isVideo = false),
            DuplicateScanner.DupItem("B", b, null, isVideo = false),
        )
        assertEquals(1, DuplicateScanner.groups(items) { _, _ -> }.size)

        // One phash null, no embeddings -> hd forced to 64 -> no group.
        val items2 = listOf(
            DuplicateScanner.DupItem("A", null, 0L, isVideo = false),
            DuplicateScanner.DupItem("B", null, null, isVideo = false),
        )
        assertTrue(DuplicateScanner.groups(items2) { _, _ -> }.isEmpty())
    }

    // --- 9. report is called with the final (N, N) ---

    @Test
    fun report_is_called_with_final_n_n() = runTest {
        val items = (0 until 40).map {
            DuplicateScanner.DupItem("id$it", null, it.toLong(), isVideo = false)
        }
        val calls = mutableListOf<Pair<Int, Int>>()
        DuplicateScanner.groups(items) { cur, total -> calls.add(cur to total) }
        assertTrue("report called at least once", calls.isNotEmpty())
        assertEquals(items.size to items.size, calls.last())
    }
}
