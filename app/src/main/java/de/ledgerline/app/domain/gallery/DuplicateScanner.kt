package de.ledgerline.app.domain.gallery

import kotlin.math.sqrt

/**
 * On-device duplicate-photo detection. Exact port of the web ground truth
 * (`resources/js/app.js`: `_dupGroupsInline` + `_dot` / `_norm`). No Android deps.
 *
 * The caller pre-normalises embeddings via [norm] (matching the web, which caches
 * `_norm(embedding)`), so [dot] over two unit vectors is a cosine and the
 * `dot >= 0.97` threshold is a cosine threshold.
 *
 * Duplicate rules (byte-for-byte with the web):
 * - `hd` = Hamming distance of the two 64-bit pHashes, or 64 if either is null.
 * - video pair (either isVideo): duplicate iff `hd <= 4`.
 * - image pair: duplicate iff `(both embeddings present && dot >= 0.97) || hd <= 3`.
 *
 * Duplicate pairs are unioned (union-find, path-halving); groups with more than one
 * member are returned, ids in ascending original-index order within each group.
 */
object DuplicateScanner {

    data class DupItem(
        val id: String,
        val embNorm: List<Double>?,
        val phash: Long?,
        val isVideo: Boolean,
    )

    /** Dot product over `min(a.size, b.size)` dims. */
    fun dot(a: List<Double>, b: List<Double>): Double {
        var d = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) d += a[i] * b[i]
        return d
    }

    /** L2-normalise. A zero vector (sum-of-squares 0) returns zeros of the same length. */
    fun norm(v: List<Double>): List<Double> {
        var s = 0.0
        for (x in v) s += x * x
        val inv = if (s > 0.0) 1.0 / sqrt(s) else 0.0
        return v.map { it * inv }
    }

    /** Hamming distance of two 64-bit values (sign-agnostic; xor + popcount). */
    fun hamming(a: Long, b: Long): Int = (a xor b).countOneBits()

    suspend fun groups(items: List<DupItem>, report: (Int, Int) -> Unit): List<List<String>> {
        val n = items.size
        val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]] // path-halving
                r = parent[r]
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        for (i in 0 until n) {
            val it1 = items[i]
            for (j in i + 1 until n) {
                val it2 = items[j]
                val hd = if (it1.phash != null && it2.phash != null) {
                    hamming(it1.phash, it2.phash)
                } else {
                    64
                }
                val dup = if (it1.isVideo || it2.isVideo) {
                    hd <= 4
                } else {
                    (it1.embNorm != null && it2.embNorm != null && dot(it1.embNorm, it2.embNorm) >= 0.97) || hd <= 3
                }
                if (dup) union(i, j)
            }
            if (i % 16 == 0) report(i, n)
        }
        report(n, n)

        val byRoot = LinkedHashMap<Int, MutableList<String>>()
        for (i in 0 until n) {
            byRoot.getOrPut(find(i)) { mutableListOf() }.add(items[i].id)
        }
        return byRoot.values.filter { it.size > 1 }.map { it.toList() }
    }
}
