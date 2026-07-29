package de.ledgerline.app.domain.gallery

import kotlin.math.sqrt

/**
 * Pure ranking helper for the semantic photo search — the byte-for-byte analogue of
 * the web's `_doSearch` scoring (`resources/js/app.js`): the query embedding and each
 * photo embedding are L2-normalised, so [cosine] over two unit vectors is a plain dot
 * product, and matches are kept while `score > threshold` (strict, matching the web's
 * `s > 0.2`), sorted by score descending, then capped.
 *
 * No Android deps; unit-tested.
 */
object SemanticSearch {

    /** Cap on returned content matches — mirrors the web `.slice(0, 80)`. */
    const val MAX_RESULTS = 80

    /**
     * The library's current CLIP model, used to gate which embeddings are comparable: web
     * reads `config.clipModel` from server bootstrap and only searches embeddings whose
     * `embModel === config.clipModel`. A native client has no such bootstrap, so it infers
     * the current model as the **modal** (most frequent) non-null `embModel` across the
     * photos' metas — the server keeps the bulk of the library re-embedded at the live
     * model, so the majority IS the current one. Returns null when no meta carries an
     * `embModel` (older untagged library) → the caller then compares all embeddings,
     * preserving prior behaviour rather than emptying search.
     */
    fun currentModel(models: List<String?>): String? =
        models.asSequence().filterNotNull().groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key

    /**
     * Cosine similarity via a plain dot product over `min(a.size, b.size)` dims.
     * Callers pass already-normalised vectors (see [norm]); for unit vectors the dot
     * product IS the cosine. Absent/empty vectors score 0.
     */
    fun cosine(a: List<Double>, b: List<Double>): Double {
        var d = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) d += a[i] * b[i]
        return d
    }

    /** L2-normalise. A zero vector returns zeros of the same length (never NaN). */
    fun norm(v: List<Double>): List<Double> {
        var s = 0.0
        for (x in v) s += x * x
        val inv = if (s > 0.0) 1.0 / sqrt(s) else 0.0
        return v.map { it * inv }
    }

    /**
     * Rank [items] (`id` -> already-normalised embedding, or null when the photo has no
     * cached embedding) against the normalised query [queryNorm]: score = cosine, keep
     * those with `score > threshold`, sort by score descending, and cap at [MAX_RESULTS].
     * Items with a null/empty embedding are dropped. Returns the surviving ids in ranked
     * order.
     */
    fun rank(
        queryNorm: List<Double>,
        items: List<Pair<String, List<Double>?>>,
        threshold: Double,
        limit: Int = MAX_RESULTS,
    ): List<String> {
        if (queryNorm.isEmpty()) return emptyList()
        return items
            .mapNotNull { (id, emb) ->
                if (emb.isNullOrEmpty()) null else id to cosine(queryNorm, emb)
            }
            .filter { (_, score) -> score > threshold }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (id, _) -> id }
    }
}
