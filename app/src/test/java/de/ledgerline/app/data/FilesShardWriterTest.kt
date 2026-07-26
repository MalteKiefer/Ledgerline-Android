package de.ledgerline.app.data

import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.NamedFolder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesShardWriterTest {

    private class FakeUploader {
        var count = 0
        val upload: suspend (ByteArray, String) -> UploadedBlob? = { bytes, _ ->
            count++
            UploadedBlob(id = "blob-$count", encFileKey = "key-$count", size = bytes.size.toLong())
        }
    }

    private fun writer(up: FakeUploader) = FilesShardWriter(
        encodeFile = { FileRecordCodec.encodeFile(it, null) },
        encodeFolder = { FileRecordCodec.encodeFolder(it, null) },
        uploadBlob = up.upload,
    )

    private fun file(id: String, name: String = "f") = FileEntry(id = id, blob = "b-$id", encFileKey = "k-$id", name = name)

    @Test fun builds_v3_root_with_shards_and_folders_ref() = runBlocking {
        val up = FakeUploader()
        val files = listOf(file("aa11"), file("bb22"))
        val folders = listOf(NamedFolder(id = "d1", name = "Docs"))

        val r = writer(up).build(files, folders, FilesShardWriter.RootState())!!

        assertEquals(3, r.rootJson["v"]!!.int())
        assertEquals(1, r.rootJson["suite"]!!.int())
        assertTrue(r.rootJson.containsKey("caps"))
        // 1 file shard (shardBits 0 → single bucket) + 1 folders collection = 2 uploads.
        assertEquals(2, up.count)
        assertTrue(r.rootJson.containsKey("foldersRef"))
        // shardRefs = shard ref + folders ref (the referential guard).
        assertEquals(2, r.shardRefs.size)
        assertEquals(1, r.state.shards.size)
    }

    @Test fun empty_folders_omits_pointer() = runBlocking {
        val up = FakeUploader()
        val r = writer(up).build(listOf(file("aa11")), emptyList(), FilesShardWriter.RootState())!!
        assertNull(r.rootJson["foldersRef"])
        assertEquals(1, up.count) // only the file shard
    }

    @Test fun dirty_save_reuses_unchanged_blobs() = runBlocking {
        val up = FakeUploader()
        val w = writer(up)
        val files = listOf(file("aa11"), file("bb22"))

        val first = w.build(files, emptyList(), FilesShardWriter.RootState())!!
        val afterFirst = up.count

        // Same files + prior state → nothing re-uploaded.
        val second = w.build(files, emptyList(), first.state)!!
        assertEquals(afterFirst, up.count)
        assertEquals(first.state.shards.map { it.ref }, second.state.shards.map { it.ref })

        // Rename a file → the (single) bucket is re-sealed → one more upload, new ref.
        val changed = listOf(file("aa11", name = "renamed"), file("bb22"))
        val third = w.build(changed, emptyList(), second.state)!!
        assertEquals(afterFirst + 1, up.count)
        assertNotEquals(second.state.shards[0].ref, third.state.shards[0].ref)
    }

    private fun kotlinx.serialization.json.JsonElement.int() =
        (this as kotlinx.serialization.json.JsonPrimitive).content.toInt()
}
