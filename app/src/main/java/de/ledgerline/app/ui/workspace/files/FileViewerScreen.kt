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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.ui.workspace.LocalFullscreen
import kotlinx.coroutines.launch

/**
 * In-app viewer that renders decrypted [bytes] held only in memory.
 * Text/code files ([isTextFile]) open in an editable monospace editor with a
 * Save action ([onSaveText], re-encrypt + versioned manifest write). Images decode
 * to a bitmap; PDFs render; anything else offers an Export action ([onExport], SAF).
 * [onExport] (SAF export to device) is always available in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(
    file: FileEntry,
    bytes: ByteArray,
    saving: Boolean,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onSaveText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }

    // Editor gate: name/MIME allowlist OR a content sniff (covers .asc/.pub/PEM keys,
    // extensionless configs, and unknown text the allowlist misses; binaries with NUL
    // bytes stay in the image/pdf/preview branches).
    val editable = remember(file.mime, file.name, bytes) { isEditableText(file.mime, file.name, bytes) }
    // Editor buffer, re-seeded whenever the underlying decrypted bytes change
    // (initial open AND after a successful save re-seeds ViewerState.Ready).
    var edited by remember(bytes) {
        mutableStateOf(TextFieldValue(if (editable) String(bytes, Charsets.UTF_8) else ""))
    }
    val original = remember(bytes) { if (editable) String(bytes, Charsets.UTF_8) else "" }
    val dirty = editable && edited.text != original

    // Offline, self-rolled syntax highlighting. The language is inferred from the
    // file name; token colors are derived from the M3 theme. Applied as a
    // VisualTransformation with OffsetMapping.Identity, which is correct because
    // highlight() never adds or removes characters — so cursor/selection stay
    // aligned and the field remains fully editable. Re-highlights as text changes.
    val lang = remember(file.name) { langOf(file.name) }
    val highlightColors = HighlightColors(
        keyword = MaterialTheme.colorScheme.primary,
        string = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
            Color(0xFF2E7D32) // green-ish string, readable on light surfaces
        } else {
            Color(0xFF9CCC65)
        },
        number = MaterialTheme.colorScheme.tertiary,
        comment = MaterialTheme.colorScheme.onSurfaceVariant,
        default = MaterialTheme.colorScheme.onSurface,
    )
    val transformation = remember(edited.text, lang, highlightColors) {
        VisualTransformation {
            TransformedText(highlight(edited.text, lang, highlightColors), OffsetMapping.Identity)
        }
    }

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
                    if (editable) {
                        if (saving) {
                            CircularProgressIndicator(
                                Modifier.padding(horizontal = 12.dp).height(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = { onSaveText(edited.text) }, enabled = dirty) {
                                Icon(Icons.Outlined.Save, stringResource(R.string.file_edit_save))
                            }
                        }
                    }
                    IconButton(onClick = onExport) {
                        Icon(Icons.Outlined.FileDownload, stringResource(R.string.file_export))
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
                editable -> {
                    androidx.compose.foundation.layout.Column(
                        Modifier.fillMaxSize().imePadding(),
                    ) {
                        BasicTextField(
                            value = edited,
                            onValueChange = { edited = it },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState()),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            visualTransformation = transformation,
                        )
                        CodeKeyBar(
                            onInsert = { edited = insertAtCursor(edited, it) },
                            onIndent = { edited = insertAtCursor(edited, "    ") },
                            onMoveLeft = { edited = moveCursor(edited, -1) },
                            onMoveRight = { edited = moveCursor(edited, 1) },
                        )
                    }
                }

                file.mime == "application/pdf" || file.name.endsWith(".pdf", ignoreCase = true) -> {
                    PdfPreview(bytes = bytes, onSave = onExport)
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
                        UnsupportedPreview(onExport)
                    }
                }

                else -> UnsupportedPreview(onExport)
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

/** Insert [s] at the cursor (replacing any selection); places the cursor after it. */
private fun insertAtCursor(v: TextFieldValue, s: String): TextFieldValue {
    val sel = v.selection
    val text = v.text.replaceRange(sel.min, sel.max, s)
    return v.copy(text = text, selection = androidx.compose.ui.text.TextRange(sel.min + s.length))
}

/** Move the (collapsed) cursor by [delta] characters, clamped to the text bounds. */
private fun moveCursor(v: TextFieldValue, delta: Int): TextFieldValue {
    val pos = (v.selection.max + delta).coerceIn(0, v.text.length)
    return v.copy(selection = androidx.compose.ui.text.TextRange(pos))
}

/** Coding symbols the soft keyboard hides behind extra taps. */
private val CODE_KEYS = listOf(
    "{", "}", "(", ")", "[", "]", "<", ">", "/", "\\", "|", "&", ";", ":",
    "=", "+", "-", "_", "*", ".", ",", "\"", "'", "`", "~", "#", "@", "$",
    "!", "?", "%", "^",
)

/** A terminal-style accessory key row above the keyboard: Tab, cursor moves, and the
 *  coding symbols. Rides above the IME via the parent's [imePadding]. */
@Composable
private fun CodeKeyBar(
    onInsert: (String) -> Unit,
    onIndent: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyCap("Tab", onIndent)
            KeyCap("←", onMoveLeft)
            KeyCap("→", onMoveRight)
            CODE_KEYS.forEach { k -> KeyCap(k) { onInsert(k) } }
        }
    }
}

@Composable
private fun KeyCap(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .widthIn(min = 40.dp),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
