package de.ledgerline.app.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes downloaded document bytes (invoice PDF, receipt) to the app cache and opens them in an
 * external viewer via a `content://` FileProvider URI with a one-shot read grant. Returns false when
 * no app can handle the type or the write fails.
 */
object DocOpener {
    fun open(context: Context, bytes: ByteArray, fileName: String, mime: String): Boolean = runCatching {
        val dir = File(context.cacheDir, "docs").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        openFile(context, file, mime)
    }.getOrDefault(false)

    /** Open an already-written cache [file] in an external viewer via a one-shot read grant. */
    fun openFile(context: Context, file: File, mime: String): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
