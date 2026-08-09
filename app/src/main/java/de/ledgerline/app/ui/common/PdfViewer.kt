package de.ledgerline.app.ui.common

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * In-app PDF viewer: renders each page to a bitmap on demand via the platform [PdfRenderer] (no
 * external app, no extra dependency). Hosted full-screen with its own top bar; [title] names the doc.
 */
@Composable
fun PdfViewerScreen(file: File, title: String, onBack: () -> Unit) {
    val pageCount by produceState(-1, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { it.pageCount }
                }
            }.getOrDefault(0)
        }
    }
    AppScaffold(topBar = { AppTopBar(title = title, onBack = onBack) }) { pad ->
        when {
            pageCount < 0 -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            pageCount == 0 -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(stringResource(R.string.files_open_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(pad).padding(8.dp)) {
                items((0 until pageCount).toList()) { index -> PdfPage(file, index) }
            }
        }
    }
}

/** One lazily-rendered PDF page. */
@Composable
private fun PdfPage(file: File, index: Int) {
    val bitmap by produceState<ImageBitmap?>(null, file, index) {
        value = withContext(Dispatchers.IO) { renderPage(file, index) }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
            ?: Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

private fun renderPage(file: File, index: Int): ImageBitmap? = runCatching {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            if (index >= renderer.pageCount) return null
            renderer.openPage(index).use { page ->
                // Render at ~1080px wide for crisp text without excessive memory.
                val targetW = 1080
                val scale = targetW.toFloat() / page.width
                val w = targetW
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp.asImageBitmap()
            }
        }
    }
}.getOrNull()
