package de.ledgerline.app.ui.workspace.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** A file the user picked from the Storage Access Framework (to upload). */
data class PickedFile(val name: String, val mime: String, val size: Long, val uri: Uri)

/**
 * Resolve DISPLAY_NAME / SIZE / mime for a content [uri] via the resolver.
 * Falls back to sane defaults when the provider omits columns.
 */
fun queryPicked(context: Context, uri: Uri): PickedFile {
    var name = "file"
    var size = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val si = c.getColumnIndex(OpenableColumns.SIZE)
        if (c.moveToFirst()) {
            if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
        }
    }
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    return PickedFile(name, mime, size, uri)
}
