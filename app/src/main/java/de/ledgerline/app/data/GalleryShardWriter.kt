package de.ledgerline.app.data

import de.ledgerline.app.core.crypto.CanonicalJson
import de.ledgerline.app.core.crypto.GallerySharding
import de.ledgerline.app.domain.model.GalleryAlbum
import de.ledgerline.app.domain.model.GalleryPerson
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.model.GalleryShard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the Store-v3 **sharded gallery root** (byte-compatible with the web
 * `sharded-store.js`): photos are bucketed into content-addressed shard blobs, albums
 * and people into collection blobs, and the root is a small pointer table
 * `{v:3, suite:1, shardBits, shards:[…], caps:{}, albumsRef/Key/Hash, peopleRef/Key/Hash}`.
 *
 * Dirty-save: a bucket whose [GallerySharding.shardHash] equals the previous
 * descriptor's hash is REUSED (its blob is not re-uploaded); only changed buckets +
 * collections are re-sealed. Superseded blobs are NOT deleted here — they become
 * orphans reclaimed by the grace-gated reconcile, exactly like the web client (a
 * concurrent writer may still reference the old ref).
 *
 * The engine is UI/network-free: [uploadBlob] is injected (it encrypts a content blob
 * + Padmé-pads + uploads, returning the blob id + wrapped key), so the whole build is
 * unit-testable. The caller seals the returned [BuildResult.rootJson] under VK and PUTs
 * it with [BuildResult.shardRefs] as the referential-guard `shards[]`.
 */
class GalleryShardWriter(
    private val encodePhoto: (GalleryPhoto) -> JsonObject,
    private val encodeAlbum: (GalleryAlbum) -> JsonObject,
    private val encodePerson: (GalleryPerson) -> JsonObject,
    private val uploadBlob: suspend (bytes: ByteArray, name: String) -> UploadedBlob?,
) {
    data class CollDesc(val ref: String, val key: String, val hash: String)

    /** The prior sealed-root state, used for dirty-save reuse. */
    data class RootState(
        val shardBits: Int = 0,
        val shards: List<GalleryShard> = emptyList(),
        val albums: CollDesc? = null,
        val people: CollDesc? = null,
    )

    data class BuildResult(val rootJson: JsonObject, val shardRefs: List<String>, val state: RootState)

    /** Build the v3 root for [photos]/[albums]/[people], reusing unchanged blobs from [prior]. */
    suspend fun build(
        photos: List<GalleryPhoto>,
        albums: List<GalleryAlbum>,
        people: List<GalleryPerson>,
        prior: RootState,
    ): BuildResult? {
        val shardBits = GallerySharding.recommendedShardBits(photos.size)
        val rebucket = shardBits != prior.shardBits

        // Bucketize (sorted bucket order for deterministic descriptor order).
        val buckets = sortedMapOf<Int, MutableList<GalleryPhoto>>()
        for (p in photos) buckets.getOrPut(GallerySharding.bucketOf(p.id, shardBits)) { mutableListOf() }.add(p)
        val priorByBucket = prior.shards.associateBy { it.bucket }

        val descriptors = mutableListOf<GalleryShard>()
        for ((bucket, recs) in buckets) {
            recs.sortBy { it.id } // id-sorted array is the hash input
            val arr = JsonArray(recs.map { encodePhoto(it) })
            val hash = GallerySharding.shardHash(arr)
            val prev = if (rebucket) null else priorByBucket[bucket]
            if (prev != null && prev.hash == hash && prev.ref.isNotEmpty()) {
                descriptors.add(prev.copy(count = recs.size, bucket = bucket)) // reuse blob
            } else {
                val blob = uploadBlob(CanonicalJson.bytes(arr), "shard.enc") ?: return null
                descriptors.add(GalleryShard(ref = blob.id, key = blob.encFileKey, hash = hash, count = recs.size, bucket = bucket))
            }
        }

        val albumsDesc = sealCollection(albums.map { encodeAlbum(it) }, prior.albums)
        val peopleDesc = sealCollection(people.map { encodePerson(it) }, prior.people)

        val root = buildJsonObject {
            put("v", 3)
            put("suite", 1)
            put("shardBits", shardBits)
            put("shards", JsonArray(descriptors.map { descriptorJson(it) }))
            put("caps", buildJsonObject { })
            albumsDesc?.let { put("albumsRef", it.ref); put("albumsKey", it.key); put("albumsHash", it.hash) }
            peopleDesc?.let { put("peopleRef", it.ref); put("peopleKey", it.key); put("peopleHash", it.hash) }
        }
        val refs = descriptors.map { it.ref } + listOfNotNull(albumsDesc?.ref, peopleDesc?.ref)
        return BuildResult(root, refs, RootState(shardBits, descriptors, albumsDesc, peopleDesc))
    }

    // (record serialization now lives in GalleryRecordCodec, injected as the encode* lambdas)

    /** Empty collection → null (pointer omitted). Reuse when the hash is unchanged. */
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
