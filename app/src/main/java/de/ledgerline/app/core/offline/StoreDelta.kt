package de.ledgerline.app.core.offline

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A record-level change set for one sealed store, captured when a save cannot reach the server
 * (offline). It records, per collection (e.g. `notes`, `photos`, `folders`), which records were
 * upserted (full new record JSON, keyed by id) and which were deleted (id list). Operation-based
 * replay is impossible here — the `save(mutate)` lambdas can't be persisted — so we diff the
 * pre-edit (base) manifest against the post-edit (local) one and store the resulting record delta.
 *
 * On reconnect the delta is applied to the *current* server head (not the stale base), giving a
 * record-granular last-write-wins merge: a record the user edited offline overwrites the server
 * copy, records untouched offline keep whatever the server (web / another device) now holds, and
 * offline deletes remove the record. This preserves concurrent edits to *other* records (§11).
 */
@Serializable
data class CollectionDelta(
    val upserts: Map<String, JsonObject> = emptyMap(),
    val deletes: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = upserts.isEmpty() && deletes.isEmpty()
}

@Serializable
data class StoreDelta(val collections: Map<String, CollectionDelta> = emptyMap()) {
    val isEmpty: Boolean get() = collections.values.all { it.isEmpty }

    /**
     * Compose [next] onto this delta (this = earlier, next = later). A later upsert of an id wins
     * over an earlier one; a later delete drops any earlier upsert of the same id and vice-versa,
     * so the composed delta reflects the net effect of both offline saves in order.
     */
    fun then(next: StoreDelta): StoreDelta {
        val keys = collections.keys + next.collections.keys
        return StoreDelta(
            keys.associateWith { k ->
                val a = collections[k] ?: CollectionDelta()
                val b = next.collections[k] ?: CollectionDelta()
                // Later delete removes earlier upsert; later upsert removes earlier delete.
                val upserts = (a.upserts.filterKeys { it !in b.deletes } + b.upserts)
                val deletes = ((a.deletes.filter { it !in b.upserts }) + b.deletes).distinct()
                CollectionDelta(upserts, deletes)
            },
        )
    }

    companion object {
        /**
         * Diff two snapshots of the same collections (id → record JSON). A record present in
         * [local] but absent/changed vs [base] becomes an upsert; a record present in [base] but
         * absent in [local] becomes a delete. Unchanged records are omitted.
         */
        fun diff(
            base: Map<String, Map<String, JsonObject>>,
            local: Map<String, Map<String, JsonObject>>,
        ): StoreDelta {
            val keys = base.keys + local.keys
            return StoreDelta(
                keys.associateWith { k ->
                    val b = base[k].orEmpty()
                    val l = local[k].orEmpty()
                    val upserts = l.filter { (id, rec) -> b[id] != rec }
                    val deletes = b.keys.filter { it !in l }
                    CollectionDelta(upserts, deletes)
                }.filterValues { !it.isEmpty },
            )
        }
    }
}
