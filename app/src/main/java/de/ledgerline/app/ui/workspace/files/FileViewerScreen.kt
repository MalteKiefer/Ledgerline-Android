package de.ledgerline.app.ui.workspace.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.FileEntry
import kotlinx.coroutines.launch

/**
 * In-app viewer that renders decrypted [bytes] held only in memory.
 * Images decode to a bitmap; text/json/xml render as selectable scrollable text;
 * anything else offers a "Save to device" action (SAF export).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(
    file: FileEntry,
    bytes: ByteArray,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(file.name.ifBlank { "(unnamed)" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onSave) {
                        Icon(Icons.Outlined.Save, stringResource(R.string.file_save))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                file.mime == "application/pdf" || file.name.endsWith(".pdf", ignoreCase = true) -> {
                    PdfPreview(bytes = bytes, onSave = onSave)
                }

                file.mime.startsWith("image/") -> {
                    val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = file.name,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        UnsupportedPreview(onSave)
                    }
                }

                file.mime.startsWith("text/") ||
                    file.mime == "application/json" ||
                    file.mime == "application/xml" -> {
                    val text = remember(bytes) { String(bytes) }
                    SelectionContainer(Modifier.fillMaxSize()) {
                        Text(
                            text,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                else -> UnsupportedPreview(onSave)
            }
        }
    }
}

/**
 * Renders a PDF from in-memory [bytes] as a lazily-rendered list of pages.
 * The [PdfRender] session is opened off the main thread and closed when this leaves
 * composition; pages are rendered one at a time as they scroll into view. No plaintext
 * is ever written to disk.
 */
@Composable
private fun PdfPreview(bytes: ByteArray, onSave: () -> Unit) {
    // State machine: null = still opening; then either an open render or a failure marker.
    var render by remember(bytes) { mutableStateOf<PdfRender?>(null) }
    var failed by remember(bytes) { mutableStateOf(false) }
    var opened by remember(bytes) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DisposableEffect(bytes) {
        val job = scope.launch {
            val r = PdfRender.open(bytes)
            if (r == null) failed = true else render = r
            opened = true
        }
        onDispose {
            job.cancel()
            render?.close()
        }
    }

    val targetWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx().toInt()
    }

    when {
        !opened -> CircularProgressIndicator()

        failed || render == null -> {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.pdf_open_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onSave) { Text(stringResource(R.string.file_save)) }
            }
        }

        else -> {
            val r = render!!
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(r.pageCount) { index ->
                    PdfPageItem(render = r, index = index, targetWidthPx = targetWidthPx)
                }
            }
        }
    }
}

/** One render outcome for a page: still rendering, a bitmap, or a failure. */
private sealed interface PageState {
    data object Loading : PageState
    data class Ready(val bitmap: Bitmap) : PageState
    data object Failed : PageState
}

/** Lazily renders a single PDF page's bitmap when it scrolls into view. */
@Composable
private fun PdfPageItem(render: PdfRender, index: Int, targetWidthPx: Int) {
    // produceState renders this page once, off the main thread and serialized by the Mutex.
    val state by produceState<PageState>(PageState.Loading, render, index, targetWidthPx) {
        val bmp = render.renderPage(index, targetWidthPx)
        value = if (bmp != null) PageState.Ready(bmp) else PageState.Failed
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.7072f) // ~A4/Letter portrait ratio for the placeholder box
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is PageState.Ready -> Image(
                bitmap = s.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.pdf_page, index + 1),
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
            PageState.Failed -> Text(
                stringResource(R.string.pdf_render_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PageState.Loading -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun UnsupportedPreview(onSave: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.file_preview_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSave) { Text(stringResource(R.string.file_save)) }
    }
}
