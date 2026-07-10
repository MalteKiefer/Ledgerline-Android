package de.ledgerline.app.domain.gallery

import de.ledgerline.app.domain.model.PersonFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceClustererTest {

    private val delta = 1e-9

    private fun face(photoId: String, idx: Int, emb: List<Double>): FaceInput =
        FaceInput(emb = emb, member = PersonFace(photoId = photoId, idx = idx))

    // --- 1. cosine ---

    @Test
    fun cosine_identical_vectors_is_one() {
        assertEquals(1.0, FaceClusterer.cosine(listOf(1.0, 2.0, 3.0), listOf(1.0, 2.0, 3.0)), delta)
    }

    @Test
    fun cosine_orthogonal_vectors_is_zero() {
        assertEquals(0.0, FaceClusterer.cosine(listOf(1.0, 0.0), listOf(0.0, 1.0)), delta)
    }

    @Test
    fun cosine_zero_vector_is_zero() {
        assertEquals(0.0, FaceClusterer.cosine(listOf(0.0, 0.0), listOf(1.0, 1.0)), delta)
        assertEquals(0.0, FaceClusterer.cosine(listOf(1.0, 1.0), listOf(0.0, 0.0)), delta)
    }

    @Test
    fun cosine_different_lengths_uses_min_length() {
        // Only the first 2 dims are compared; the trailing 100.0 is ignored.
        assertEquals(1.0, FaceClusterer.cosine(listOf(1.0, 0.0), listOf(1.0, 0.0, 100.0)), delta)
    }

    // --- 2. Full scan: near-identical pair clusters, lone orthogonal dropped ---

    @Test
    fun full_scan_two_near_identical_faces_form_one_cluster_and_lone_face_dropped() {
        val faces = listOf(
            face("p1", 0, listOf(1.0, 0.0)),
            face("p2", 0, listOf(0.99, 0.14)), // cosine with [1,0] ~= 0.99 > 0.5
            face("p3", 0, listOf(0.0, 1.0)),   // orthogonal, lone -> dropped
        )
        val result = FaceClusterer.cluster(faces, emptyList(), emptyList(), incremental = false)

        assertEquals(1, result.size)
        val c = result[0]
        assertNull(c.id)
        assertEquals(2, c.faces.size)
        assertEquals(setOf("p1", "p2"), c.faces.map { it.photoId }.toSet())
    }

    // --- 3. Min-2 drop ---

    @Test
    fun single_face_yields_empty_result() {
        val faces = listOf(face("p1", 0, listOf(1.0, 0.0)))
        val result = FaceClusterer.cluster(faces, emptyList(), emptyList(), incremental = false)
        assertTrue(result.isEmpty())
    }

    // --- 4. Incremental with seed cluster ---

    @Test
    fun incremental_new_face_merges_into_seed_and_duplicate_member_not_readded() {
        val seedMembers = listOf(
            PersonFace(photoId = "s1", idx = 0),
            PersonFace(photoId = "s2", idx = 0),
        )
        val seed = SeedCluster(
            id = "person-1",
            name = "Alice",
            hidden = false,
            centroid = listOf(1.0, 0.0),
            members = seedMembers,
        )
        val faces = listOf(
            // A seed member passed again -> already placed, must not be double-added.
            face("s1", 0, listOf(1.0, 0.0)),
            // A new face matching the seed centroid (cosine > 0.5) -> merges into seed.
            face("p9", 0, listOf(0.99, 0.14)),
        )
        val result = FaceClusterer.cluster(faces, listOf(seed), emptyList(), incremental = true)

        assertEquals(1, result.size)
        val c = result[0]
        assertEquals("person-1", c.id)
        assertEquals("Alice", c.name)
        // 2 seed members + 1 new face; the re-passed s1 is NOT re-added.
        assertEquals(3, c.faces.size)
        assertEquals(listOf("s1", "s2", "p9"), c.faces.map { it.photoId })
    }

    // --- 5. Name carry-over (full scan only) ---

    @Test
    fun full_scan_carries_name_and_hidden_from_matching_prev_person() {
        val faces = listOf(
            face("p1", 0, listOf(1.0, 0.0)),
            face("p2", 0, listOf(0.99, 0.14)),
        )
        val prev = listOf(
            PrevPerson(name = "Bob", hidden = true, centroid = listOf(1.0, 0.0)), // cosine > 0.6
        )
        val result = FaceClusterer.cluster(faces, emptyList(), prev, incremental = false)

        assertEquals(1, result.size)
        assertEquals("Bob", result[0].name)
        assertEquals(true, result[0].hidden)
    }

    @Test
    fun full_scan_below_threshold_prev_does_not_carry_name() {
        val faces = listOf(
            face("p1", 0, listOf(1.0, 0.0)),
            face("p2", 0, listOf(0.99, 0.14)),
        )
        // Orthogonal prev centroid -> cosine 0.0, not > 0.6.
        val prev = listOf(PrevPerson(name = "Bob", hidden = true, centroid = listOf(0.0, 1.0)))
        val result = FaceClusterer.cluster(faces, emptyList(), prev, incremental = false)

        assertEquals(1, result.size)
        assertEquals("", result[0].name)
        assertEquals(false, result[0].hidden)
    }

    @Test
    fun full_scan_skips_prev_with_empty_centroid() {
        val faces = listOf(
            face("p1", 0, listOf(1.0, 0.0)),
            face("p2", 0, listOf(0.99, 0.14)),
        )
        val prev = listOf(PrevPerson(name = "Ghost", hidden = true, centroid = emptyList()))
        val result = FaceClusterer.cluster(faces, emptyList(), prev, incremental = false)

        assertEquals(1, result.size)
        assertEquals("", result[0].name)
        assertEquals(false, result[0].hidden)
    }

    // --- 6. Sort by size descending ---

    @Test
    fun clusters_sorted_by_member_count_descending() {
        // Cluster A around [1,0]: 3 members. Cluster B around [0,1]: 2 members.
        val faces = listOf(
            face("a1", 0, listOf(1.0, 0.0)),
            face("a2", 0, listOf(0.99, 0.14)),
            face("a3", 0, listOf(0.98, 0.19)),
            face("b1", 0, listOf(0.0, 1.0)),
            face("b2", 0, listOf(0.14, 0.99)),
        )
        val result = FaceClusterer.cluster(faces, emptyList(), emptyList(), incremental = false)

        assertEquals(2, result.size)
        assertEquals(3, result[0].faces.size)
        assertEquals(2, result[1].faces.size)
    }
}
