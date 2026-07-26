package de.ledgerline.app.core.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/**
 * The byte size of a `content://` blob. Prefers the cheap `OpenableColumns.SIZE` metadata; falls
 * back to a streaming byte-count (constant memory, never loads the whole file) when it is absent.
 * Returns 0 only when the item can't be opened. Needed so large-video uploads stream with a
 * correct content length instead of buffering into RAM.
 */
fun blobSize(resolver: ContentResolver, uri: Uri): Long {
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && !c.isNull(i)) return c.getLong(i)
            }
        }
    }
    return runCatching {
        resolver.openInputStream(uri)?.use { ins ->
            var total = 0L
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n < 0) break; total += n }
            total
        } ?: 0L
    }.getOrDefault(0L)
}
