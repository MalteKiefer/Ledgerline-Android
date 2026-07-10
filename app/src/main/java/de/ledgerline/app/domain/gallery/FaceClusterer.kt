package de.ledgerline.app.domain.gallery

import de.ledgerline.app.domain.model.PersonFace
import kotlin.math.sqrt

/**
 * On-device face clustering. Exact port of the web ground truth
 * (`resources/js/app.js`: `_faceClustersInline` + `cosine`). No Android deps.
 */
data class FaceInput(val emb: List<Double>, val member: PersonFace)

data class SeedCluster(
    val id: String,
    val name: String,
    val hidden: Boolean,
    val centroid: List<Double>,
    val members: List<PersonFace>,
)

data class PrevPerson(val name: String, val hidden: Boolean, val centroid: List<Double>)

/** Result person carries `id == null` for brand-new clusters (caller assigns an id). */
data class ClusterResult(
    val id: String?,
    val name: String,
    val hidden: Boolean,
    val centroid: List<Double>,
    val faces: List<PersonFace>,
)

object FaceClusterer {

    /** Cosine similarity over `min(a.size, b.size)` dims; 0.0 if either norm is 0. */
    fun cosine(a: List<Double>, b: List<Double>): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na != 0.0 && nb != 0.0) dot / (sqrt(na) * sqrt(nb)) else 0.0
    }

    private class MutableCluster(
        val id: String?,
        var name: String,
        var hidden: Boolean,
        val centroid: DoubleArray,
        var count: Int,
        val members: MutableList<PersonFace>,
    )

    fun cluster(
        faces: List<FaceInput>,
        seeds: List<SeedCluster>,
        prev: List<PrevPerson>,
        incremental: Boolean,
    ): List<ClusterResult> {
        val clusters = ArrayList<MutableCluster>()
        val placed = HashSet<String>()

        for (s in seeds) {
            clusters.add(
                MutableCluster(
                    id = s.id,
                    name = s.name,
                    hidden = s.hidden,
                    centroid = s.centroid.toDoubleArray(),
                    count = s.members.size,
                    members = s.members.toMutableList(),
                )
            )
            for (m in s.members) placed.add(m.photoId + ":" + m.idx)
        }

        for (face in faces) {
            val key = face.member.photoId + ":" + face.member.idx
            if (placed.contains(key)) continue
            placed.add(key)

            var best: MutableCluster? = null
            var bestSim = 0.5
            for (c in clusters) {
                val s = cosine(face.emb, c.centroid.asList())
                if (s > bestSim) {
                    bestSim = s
                    best = c
                }
            }

            if (best != null) {
                val n = best.count
                for (i in best.centroid.indices) {
                    best.centroid[i] = (best.centroid[i] * n + face.emb[i]) / (n + 1)
                }
                best.count = n + 1
                best.members.add(face.member)
            } else {
                clusters.add(
                    MutableCluster(
                        id = null,
                        name = "",
                        hidden = false,
                        centroid = face.emb.toDoubleArray(),
                        count = 1,
                        members = mutableListOf(face.member),
                    )
                )
            }
        }

        return clusters
            .filter { it.members.size >= 2 }
            .sortedByDescending { it.members.size } // stable, matches JS Array.sort
            .map { c ->
                var name = c.name
                var hidden = c.hidden
                if (!incremental) {
                    var bestSim = 0.6
                    var match: PrevPerson? = null
                    for (pp in prev) {
                        if (pp.centroid.isEmpty()) continue
                        val s = cosine(c.centroid.asList(), pp.centroid)
                        if (s > bestSim) {
                            bestSim = s
                            match = pp
                        }
                    }
                    if (match != null) {
                        name = match.name
                        hidden = match.hidden
                    }
                }
                ClusterResult(
                    id = c.id,
                    name = name,
                    hidden = hidden,
                    centroid = c.centroid.toList(),
                    faces = c.members.toList(),
                )
            }
    }
}
