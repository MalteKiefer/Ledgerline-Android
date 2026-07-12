package de.ledgerline.app.data.backup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Aggregates MediaStore image+video buckets into a [DeviceAlbum] list for the picker. */
class DeviceAlbums(private val resolver: ContentResolver) {

    @Inject constructor(@ApplicationContext context: Context) : this(context.contentResolver)

    fun list(): List<DeviceAlbum> {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns._ID,
        )
        val selection =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}," +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val sort = "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC"

        data class Agg(var name: String, var count: Int, var sampleId: Long)
        val map = LinkedHashMap<String, Agg>()
        resolver.query(uri, projection, selection, null, sort)?.use { c ->
            val bIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val nIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            while (c.moveToNext()) {
                val bucket = c.getString(bIdx) ?: continue
                val name = c.getString(nIdx) ?: bucket
                val id = c.getLong(idIdx)
                val agg = map.getOrPut(bucket) { Agg(name, 0, id) }
                agg.count++
            }
        }
        return map.map { (bucket, agg) ->
            DeviceAlbum(bucket, agg.name, agg.count, ContentUris.withAppendedId(uri, agg.sampleId))
        }
    }
}
