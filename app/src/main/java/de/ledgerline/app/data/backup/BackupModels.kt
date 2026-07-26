package de.ledgerline.app.data.backup

import android.net.Uri

/** A device photo album (MediaStore bucket) shown in the picker. */
data class DeviceAlbum(val bucketId: String, val name: String, val count: Int, val sampleUri: Uri?)

/** A candidate device media item to back up. */
data class BackupItem(
    val mediaStoreId: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val dateTakenMs: Long,
)
