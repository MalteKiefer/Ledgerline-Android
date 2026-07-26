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
class BackupScannerTest {
    private val cols = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_TAKEN,
    )

    private fun cursorOf(vararg rows: Array<Any?>) = MatrixCursor(cols).apply { rows.forEach { addRow(it) } }

    @Test fun `maps rows to BackupItems`() {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), any(), any(), any()) } returns
            cursorOf(
                arrayOf(7L, "IMG_1.jpg", "image/jpeg", 1234L, 1_000L),
                arrayOf(8L, "VID_2.mp4", "video/mp4", 9999L, 2_000L),
            )

        val items = BackupScanner(resolver).scan(setOf("bucketA"))

        assertEquals(2, items.size)
        assertEquals(7L, items[0].mediaStoreId)
        assertEquals("IMG_1.jpg", items[0].name)
        assertEquals("image/jpeg", items[0].mime)
        assertEquals(1234L, items[0].sizeBytes)
        assertEquals("video/mp4", items[1].mime)
    }

    @Test fun `empty buckets set returns empty without querying`() {
        val resolver = mockk<ContentResolver>()
        assertEquals(emptyList<BackupItem>(), BackupScanner(resolver).scan(emptySet()))
    }
}
