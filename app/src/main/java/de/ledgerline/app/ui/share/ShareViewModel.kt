package de.ledgerline.app.ui.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.ops.OpKind
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.domain.usecase.ImportFile
import de.ledgerline.app.domain.usecase.ImportPhotos
import de.ledgerline.app.domain.usecase.PhotoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Drives the share-target confirm sheet: exposes the workspace folder list for the
 * files picker and runs the actual import through the shared [ImportPhotos]/[ImportFile]
 * use-cases via [OperationManager] (so the work survives backgrounding and feeds the
 * shared progress overlay + service notification).
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importPhotos: ImportPhotos,
    private val importFile: ImportFile,
    workspaceCache: WorkspaceCache,
    private val operationManager: OperationManager,
) : ViewModel() {

    /** Flat list of workspace file folders for the target-folder dropdown. */
    val fileFolders: StateFlow<List<NamedFolder>> =
        workspaceCache.value
            .map { it?.manifest?.fileFolders?.distinctBy { f -> f.id } ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun clearMessage() { _message.value = null }

    /**
     * Import the shared [items]: gallery items run through [ImportPhotos] (dedup +
     * upload + gallery-index append), file items through [ImportFile] into
     * [targetFolder] (null = root). Both run as independent [OperationManager] ops so
     * their progress feeds the shared overlay; failures are summed and surfaced as
     * `"import_done:<ok>:<failed>"` in [message].
     */
    fun import(items: List<SharedItem>, targetFolder: String?) {
        val gallery = items.filter { it.target == ShareTarget.GALLERY }
        val files = items.filter { it.target == ShareTarget.FILES }
        val total = gallery.size + files.size
        if (total == 0) return

        val failed = java.util.concurrent.atomic.AtomicInteger(0)
        val remainingOps = java.util.concurrent.atomic.AtomicInteger(
            (if (gallery.isNotEmpty()) 1 else 0) + (if (files.isNotEmpty()) 1 else 0),
        )

        fun finishOp() {
            if (remainingOps.decrementAndGet() == 0) {
                val f = failed.get()
                _message.value = "import_done:${total - f}:$f"
            }
        }

        if (gallery.isNotEmpty()) {
            val sources = gallery.map { it.toPhotoSource() }
            operationManager.run(OpKind.UPLOAD, total = sources.size) { report ->
                try {
                    val result = importPhotos.invoke(sources, report)
                    failed.addAndGet(result.failed)
                } finally {
                    finishOp()
                }
            }
        }

        if (files.isNotEmpty()) {
            operationManager.run(OpKind.UPLOAD, total = files.size) { report ->
                try {
                    var done = 0
                    report(0, files.size)
                    for (item in files) {
                        val size = sizeOf(item.uri)
                        val res = importFile.invoke(item.name, item.mime, size, targetFolder) {
                            context.contentResolver.openInputStream(item.uri)
                                ?: error("cannot open ${item.uri}")
                        }
                        if (res is de.ledgerline.app.core.Outcome.Err) failed.incrementAndGet()
                        report(++done, files.size)
                    }
                } finally {
                    finishOp()
                }
            }
        }
    }

    private fun SharedItem.toPhotoSource() = PhotoSource(
        name = name,
        mime = mime,
        read = {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("cannot open $uri")
        },
        lat = null,
        lng = null,
    )

    /** Best-effort content size via OpenableColumns.SIZE. Falls back to reading the
     *  stream length (the encrypt/upload path also needs a length up front). */
    private fun sizeOf(uri: Uri): Long {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && !c.isNull(idx)) {
                            val s = c.getLong(idx)
                            if (s >= 0) return s
                        }
                    }
                }
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                var count = 0L
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    count += r
                }
                count
            } ?: 0L
        }.getOrDefault(0L)
    }
}
