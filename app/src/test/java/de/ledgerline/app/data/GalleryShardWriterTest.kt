package de.ledgerline.app.data

import de.ledgerline.app.domain.model.GalleryAlbum
import de.ledgerline.app.domain.model.GalleryPhoto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryShardWriterTest {

    private class FakeUploader {
        var count = 0
        val upload: suspend (ByteArray, String) -> UploadedBlob? = { bytes, _ ->
            count++
            UploadedBlob(id = "blob-$count", encFileKey = "key-$count", size = bytes.size.toLong())
        }
    }

    private fun photo(id: String, name: String = "p") =
        GalleryPhoto(id = id, name = name)

    @Test fun builds_v3_root_with_shards_and_collection_refs() = runBlocking {
        val up = FakeUploader()
        val writer = GalleryShardWriter(
            encodePhoto = { GalleryRecordCodec.encodePhoto(it, null) },
            encodeAlbum = { GalleryRecordCodec.encodeAlbum(it, null) },
            encodePerson = { GalleryRecordCodec.encodePerson(it, null) },
            uploadBlob = up.upload,
        )
        val photos = listOf(photo("aa11"), photo("bb22"))
        val albums = listOf(GalleryAlbum(id = "al1", name = "Trip"))

        val r = writer.build(photos, albums, people = emptyList(), prior = GalleryShardWriter.RootState())!!

        assertEquals(3, r.rootJson["v"]!!.jsonPrimitive.int())
        assertEquals(1, r.rootJson["suite"]!!.jsonPrimitive.int())
        // 1 photo shard (shardBits 0 → single bucket) + 1 album collection = 2 uploads.
        assertEquals(2, up.count)
        assertTrue(r.rootJson.containsKey("albumsRef"))
        assertNull(r.rootJson["peopleRef"]) // empty people → omitted
        // shardRefs = shard ref + album ref.
        assertEquals(2, r.shardRefs.size)
        assertEquals(1, r.state.shards.size)
    }

    @Test fun dirty_save_reuses_unchanged_blobs() = runBlocking {
        val up = FakeUploader()
        val writer = GalleryShardWriter(
            encodePhoto = { GalleryRecordCodec.encodePhoto(it, null) },
            encodeAlbum = { GalleryRecordCodec.encodeAlbum(it, null) },
            encodePerson = { GalleryRecordCodec.encodePerson(it, null) },
            uploadBlob = up.upload,
        )
        val photos = listOf(photo("aa11"), photo("bb22"))

        val first = writer.build(photos, emptyList(), emptyList(), GalleryShardWriter.RootState())!!
        val uploadsAfterFirst = up.count

        // Same photos + prior state → nothing re-uploaded.
        val second = writer.build(photos, emptyList(), emptyList(), first.state)!!
        assertEquals(uploadsAfterFirst, up.count)
        assertEquals(first.state.shards.map { it.ref }, second.state.shards.map { it.ref })

        // Change a photo → the (single) bucket is re-sealed → one more upload, new ref.
        val changed = listOf(photo("aa11", name = "renamed"), photo("bb22"))
        val third = writer.build(changed, emptyList(), emptyList(), second.state)!!
        assertEquals(uploadsAfterFirst + 1, up.count)
        assertNotEquals(second.state.shards[0].ref, third.state.shards[0].ref)
    }

    private fun kotlinx.serialization.json.JsonPrimitive.int() = content.toInt()
}
