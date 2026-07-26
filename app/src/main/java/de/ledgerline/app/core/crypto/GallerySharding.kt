package de.ledgerline.app.core.crypto

import kotlinx.serialization.json.JsonArray
import java.security.MessageDigest

/**
 * Store-v3 sharding, byte-compatible with the web `resources/js/shared/shard.js`.
 * Record ids are lowercase hex (128-bit CSPRNG); a record's bucket is the top
 * [shardBits] bits of the uint32 formed by its first 8 hex chars. Shard content is
 * content-addressed by [shardHash] = SHA-256 over the canonical JSON of the id-sorted
 * record array — so these formulas MUST match the web client to avoid cross-client
 * shard churn.
 */
object GallerySharding {

    /** The bucket index for a record [id] at [shardBits] (top shardBits of uint32(id[0:8])). */
    fun bucketOf(id: String, shardBits: Int): Int {
        if (shardBits <= 0) return 0 // single shard (avoids ushr 32 == ushr 0 trap)
        if (id.length < 8) return 0
        val prefix = try {
            id.substring(0, 8).toLong(16) and 0xFFFFFFFFL
        } catch (_: NumberFormatException) {
            return 0
        }
        return (prefix ushr (32 - shardBits)).toInt()
    }

    /** Grow shardBits from 0, +1 whenever the mean bucket size would exceed 500. */
    fun recommendedShardBits(count: Int): Int {
        var bits = 0
        while (count.toDouble() / (1L shl bits) > 500) bits++
        return bits
    }

    /** Lowercase-hex SHA-256 over the canonical JSON of [records] (must be id-sorted). */
    fun shardHash(records: JsonArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(records))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
