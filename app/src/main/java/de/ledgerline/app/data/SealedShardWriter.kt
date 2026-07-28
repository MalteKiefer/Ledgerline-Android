package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.CanonicalJson
import de.ledgerline.app.core.crypto.GallerySharding
import de.ledgerline.app.domain.model.GalleryShard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Generic Store-v3 **sharded root** builder (byte-compatible with the web `sharded-store.js`):
 * records are bucketed into content-addressed shard blobs and an optional single collection into
 * a collection blob; the root is a pointer table `{v:3, suite:1, shardBits, shards:[…], caps:{},
 * foldersRef/Key/Hash?}`. Same dirty-save reuse as [FilesShardWriter]/[GalleryShardWriter], but
 * generic over pre-encoded `(id, JsonObject)` records + `List<JsonObject>` collection items, so
 * notes (no collection) and passwords (one `secretFolders` collection) share one engine.
 */
class SealedShardWriter(
    private val uploadBlob: suspend (bytes: ByteArray, name: String) -> UploadedBlob?,
) {
    data class CollDesc(val ref: String, val key: String, val hash: String)
    data class RootState(
        val shardBits: Int = 0,
        val shards: List<GalleryShard> = emptyList(),
        val folders: CollDesc? = null,
    )
    data class BuildResult(val rootJson: JsonObject, val shardRefs: List<String>, val state: RootState)

    /** Build the v3 root for [records] (id → encoded JSON) + optional [folders], reusing unchanged blobs. */
    suspend fun build(records: List<Pair<String, JsonObject>>, folders: List<JsonObject>, prior: RootState): BuildResult? {
        val shardBits = GallerySharding.recommendedShardBits(records.size)
        val rebucket = shardBits != prior.shardBits

        val buckets = sortedMapOf<Int, MutableList<Pair<String, JsonObject>>>()
        for (r in records) buckets.getOrPut(GallerySharding.bucketOf(r.first, shardBits)) { mutableListOf() }.add(r)
        val priorByBucket = prior.shards.associateBy { it.bucket }

        val descriptors = mutableListOf<GalleryShard>()
        for ((bucket, recs) in buckets) {
            recs.sortBy { it.first } // id-sorted array is the hash input
            val arr = JsonArray(recs.map { it.second })
            val hash = GallerySharding.shardHash(arr)
            val prev = if (rebucket) null else priorByBucket[bucket]
            if (prev != null && prev.hash == hash && prev.ref.isNotEmpty()) {
                descriptors.add(prev.copy(count = recs.size, bucket = bucket))
            } else {
                val blob = uploadBlob(CanonicalJson.bytes(arr), "shard.enc") ?: return null
                descriptors.add(GalleryShard(ref = blob.id, key = blob.encFileKey, hash = hash, count = recs.size, bucket = bucket))
            }
        }

        val foldersDesc = sealCollection(folders, prior.folders)

        val root = buildJsonObject {
            put("v", 3)
            put("suite", 1)
            put("shardBits", shardBits)
            put("shards", JsonArray(descriptors.map { descriptorJson(it) }))
            put("caps", buildJsonObject { })
            foldersDesc?.let { put("foldersRef", it.ref); put("foldersKey", it.key); put("foldersHash", it.hash) }
        }
        val refs = descriptors.map { it.ref } + listOfNotNull(foldersDesc?.ref)
        return BuildResult(root, refs, RootState(shardBits, descriptors, foldersDesc))
    }

    private suspend fun sealCollection(items: List<JsonElement>, prior: CollDesc?): CollDesc? {
        if (items.isEmpty()) return null
        val arr = JsonArray(items)
        val hash = GallerySharding.shardHash(arr)
        if (prior != null && prior.hash == hash && prior.ref.isNotEmpty()) return prior
        val blob = uploadBlob(CanonicalJson.bytes(arr), "collection.enc") ?: return null
        return CollDesc(ref = blob.id, key = blob.encFileKey, hash = hash)
    }

    private fun descriptorJson(s: GalleryShard): JsonObject = buildJsonObject {
        put("ref", s.ref)
        put("key", s.key)
        put("hash", s.hash ?: "")
        put("count", s.count)
        put("bucket", s.bucket)
    }
}
