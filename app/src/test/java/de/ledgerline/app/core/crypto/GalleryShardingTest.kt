package de.ledgerline.app.core.crypto

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Store-v3 sharding conformance (web `shard.js`). */
class GalleryShardingTest {

    @Test fun bucket_of_top_bits() {
        assertEquals(0, GallerySharding.bucketOf("00000000abcd", 4))
        assertEquals(0, GallerySharding.bucketOf("deadbeef", 0))            // shardBits 0 → single shard
        assertEquals(1, GallerySharding.bucketOf("80000000ffff", 1))       // top bit set
        assertEquals(0, GallerySharding.bucketOf("7fffffffffff", 1))       // top bit clear
        assertEquals(15, GallerySharding.bucketOf("ffffffff0000", 4))      // top 4 bits all set
        assertEquals(0, GallerySharding.bucketOf("zz", 4))                 // malformed → 0
    }

    @Test fun recommended_shard_bits() {
        assertEquals(0, GallerySharding.recommendedShardBits(0))
        assertEquals(0, GallerySharding.recommendedShardBits(500))   // strict > → 500 does not split
        assertEquals(1, GallerySharding.recommendedShardBits(501))
        assertEquals(1, GallerySharding.recommendedShardBits(1000)) // 1000/2=500, not > 500
        assertEquals(3, GallerySharding.recommendedShardBits(2001)) // /4 = 500.25 > 500 → 3
    }

    @Test fun shard_hash_is_deterministic_and_content_addressed() {
        val a = buildJsonArray { add(buildJsonObject { put("id", "a"); put("x", 1) }) }
        val b = buildJsonArray { add(buildJsonObject { put("id", "a"); put("x", 1) }) }
        val c = buildJsonArray { add(buildJsonObject { put("id", "a"); put("x", 2) }) }
        assertEquals(GallerySharding.shardHash(a), GallerySharding.shardHash(b))
        assertNotEquals(GallerySharding.shardHash(a), GallerySharding.shardHash(c))
        assertEquals(64, GallerySharding.shardHash(a).length) // 32-byte SHA-256 → 64 hex
    }
}
