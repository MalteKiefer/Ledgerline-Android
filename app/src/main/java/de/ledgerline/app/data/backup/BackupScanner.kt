package de.ledgerline.app.data.backup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Lists candidate device media (images + videos) in the given MediaStore buckets,
 * newest first. Pure mapping over an injected [ContentResolver] so it is unit-testable
 * with a stubbed cursor.
 */
class BackupScanner(private val resolver: ContentResolver) {

    @Inject constructor(@ApplicationContext context: Context) : this(context.contentResolver)

    fun scan(bucketIds: Set<String>): List<BackupItem> {
        if (bucketIds.isEmpty()) return emptyList()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
        )
        val mediaTypeSel =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}," +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val bucketSel = bucketIds.joinToString(",") { "?" }
        val selection = "$mediaTypeSel AND ${MediaStore.Files.FileColumns.BUCKET_ID} IN ($bucketSel)"
        val args = bucketIds.toTypedArray()
        val sort = "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC"

        val items = ArrayList<BackupItem>()
        resolver.query(uri, projection, selection, args, sort)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                items.add(
                    BackupItem(
                        mediaStoreId = id,
                        uri = ContentUris.withAppendedId(uri, id),
                        name = c.getString(nameIdx) ?: "IMG_$id",
                        mime = c.getString(mimeIdx) ?: "application/octet-stream",
                        sizeBytes = c.getLong(sizeIdx),
                        dateTakenMs = c.getLong(dateIdx),
                    ),
                )
            }
        }
        return items
    }
}
