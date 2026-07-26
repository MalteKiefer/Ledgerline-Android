package de.ledgerline.app.data.backup

import android.content.ContentResolver
import android.database.MatrixCursor
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceAlbumsTest {
    private val cols = arrayOf(
        MediaStore.Files.FileColumns.BUCKET_ID,
        MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
        MediaStore.Files.FileColumns._ID,
    )

    @Test fun `aggregates buckets with counts`() {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), any(), any(), any()) } returns
            MatrixCursor(cols).apply {
                addRow(arrayOf<Any?>("b1", "Camera", 10L))
                addRow(arrayOf<Any?>("b1", "Camera", 11L))
                addRow(arrayOf<Any?>("b2", "Screenshots", 20L))
            }

        val albums = DeviceAlbums(resolver).list()

        assertEquals(2, albums.size)
        assertEquals("Camera", albums.first { it.bucketId == "b1" }.name)
        assertEquals(2, albums.first { it.bucketId == "b1" }.count)
        assertEquals(1, albums.first { it.bucketId == "b2" }.count)
    }
}
