package de.ledgerline.app.ui.files

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.files.FileEntry
import de.ledgerline.app.domain.model.files.FileLabel
import de.ledgerline.app.domain.model.files.FileVersion
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.ConfirmDialog
import de.ledgerline.app.ui.common.DocOpener
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.common.TextInputDialog
import de.ledgerline.app.ui.theme.cardSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREVIEW_MAX_BYTES = 8L * 1024 * 1024

private sealed interface Preview {
    data class Img(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : Preview
    data class Txt(val text: String) : Preview
    data object None : Preview
}

/** File detail: inline image/text preview, metadata, label assignment, version history + actions. */
@Composable
fun FileDetailScreen(vm: FilesViewModel, fileId: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val data by vm.data.collectAsStateWithLifecycle()
    val file = data?.files?.firstOrNull { it.id == fileId }

    if (file == null) { onBack(); return }

    var preview by remember(fileId, file.version) { mutableStateOf<Preview?>(null) }
    var versions by remember(fileId) { mutableStateOf<List<FileVersion>>(emptyList()) }
    var editLabels by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    val replaceLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) scope.launch {
            val tmp = withContext(Dispatchers.IO) {
                val name = file.name
                val dir = java.io.File(ctx.cacheDir, "uploads").apply { mkdirs() }
                val dest = java.io.File(dir, "replace_${file.id}")
                ctx.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                Triple(dest, name, ctx.contentResolver.getType(uri))
            }
            vm.replaceContent(file.id, tmp.first, tmp.second, tmp.third) { ok ->
                runCatching { tmp.first.delete() }
                msg = ctx.getString(if (ok) R.string.files_new_version_added else R.string.files_save_failed)
            }
        }
    }

    LaunchedEffect(fileId, file.version) {
        versions = vm.versions(fileId)
        preview = loadPreview(vm, file)
    }

    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(file.mime ?: "application/octet-stream"),
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = ctx.contentResolver.openOutputStream(uri)?.use { withContext(Dispatchers.IO) { vm.saveTo(file, it) } } ?: false
            msg = ctx.getString(if (ok) R.string.files_saved else R.string.files_save_failed)
        }
    }

    AppScaffold(topBar = {
        AppTopBar(title = file.name, onBack = onBack, actions = {
            IconButton(onClick = { vm.setFavorite(file.id, !file.favorite) {} }) {
                Icon(if (file.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = stringResource(R.string.action_favorite))
            }
        })
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            // Preview
            when (val p = preview) {
                is Preview.Img -> Image(p.bitmap, contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit)
                is Preview.Txt -> Text(
                    p.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).cardSurface().verticalScroll(rememberScrollState()),
                )
                else -> {}
            }

            // Actions
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { scope.launch { openExternal(vm, ctx, file) { msg = it } } }, label = { Text(stringResource(R.string.action_open)) })
                AssistChip(onClick = { saveLauncher.launch(file.name) }, label = { Text(stringResource(R.string.files_save_device)) })
                AssistChip(onClick = { share = true }, label = { Text(stringResource(R.string.action_share)) })
                AssistChip(onClick = { replaceLauncher.launch("*/*") }, label = { Text(stringResource(R.string.files_replace_content)) })
                AssistChip(onClick = { rename = true }, label = { Text(stringResource(R.string.action_rename)) })
                AssistChip(onClick = { move = true }, label = { Text(stringResource(R.string.action_move)) })
                AssistChip(onClick = { confirmDelete = true }, label = { Text(stringResource(R.string.action_delete)) })
            }

            // Metadata
            SectionLabel(stringResource(R.string.files_type))
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaRow(stringResource(R.string.files_type), file.mime ?: "—")
                MetaRow(stringResource(R.string.files_size), formatBytes(file.size))
                file.updatedAt?.let { MetaRow(stringResource(R.string.files_modified), it.take(19).replace('T', ' ')) }
                if (file.tags.isNotEmpty()) MetaRow(stringResource(R.string.files_tags), file.tags.joinToString(", "))
                file.note?.takeIf { it.isNotBlank() }?.let { MetaRow(stringResource(R.string.files_note), it) }
            }

            // Labels
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.files_labels), Modifier.weight(1f))
                TextButton(onClick = { editLabels = true }) { Text(stringResource(R.string.action_edit)) }
            }
            if (file.labels.isEmpty()) {
                Text(stringResource(R.string.files_no_labels), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                file.labels.forEach { l -> AssistChip(onClick = {}, label = { Text(l.name) }) }
            }

            // Versions
            if (versions.isNotEmpty()) {
                SectionLabel(stringResource(R.string.files_versions))
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    versions.forEach { v ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(formatBytes(v.size), style = MaterialTheme.typography.bodyMedium)
                                v.createdAt?.let { Text(it.take(19).replace('T', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            TextButton(onClick = { vm.restoreVersion(file.id, v.id) {} }) { Text(stringResource(R.string.files_restore_version)) }
                        }
                    }
                }
            }
        }
    }

    if (editLabels) LabelPickerDialog(
        all = data?.labels.orEmpty().filter { true },
        selected = file.labels.map { it.id }.toSet(),
        onConfirm = { ids -> editLabels = false; vm.setLabels(file.id, ids.toList()) {} },
        onDismiss = { editLabels = false },
    )
    if (rename) TextInputDialog(
        title = stringResource(R.string.action_rename), label = stringResource(R.string.files_folder_name),
        confirmLabel = stringResource(R.string.action_save), initial = file.name,
        onConfirm = { name -> rename = false; vm.renameFile(file.id, name) {} }, onDismiss = { rename = false },
    )
    if (move) MoveDialog(
        folders = data?.folders.orEmpty().filter { it.deletedAt == null },
        currentParent = file.folderId,
        onPick = { dest -> move = false; vm.moveFile(file.id, dest) {} },
        onDismiss = { move = false },
    )
    if (confirmDelete) ConfirmDialog(
        message = stringResource(R.string.files_delete_confirm), confirmLabel = stringResource(R.string.action_delete),
        onConfirm = { confirmDelete = false; vm.deleteFile(file.id) { onBack() } }, onDismiss = { confirmDelete = false },
    )
    if (share) ShareDialog(vm, kind = "file", id = file.id, folderId = null, onDismiss = { share = false })
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun LabelPickerDialog(all: List<FileLabel>, selected: Set<Int>, onConfirm: (Set<Int>) -> Unit, onDismiss: () -> Unit) {
    var sel by remember { mutableStateOf(selected) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_labels)) },
        text = {
            if (all.isEmpty()) Text(stringResource(R.string.files_no_labels))
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                all.forEach { l ->
                    FilterChip(
                        selected = l.id in sel,
                        onClick = { sel = if (l.id in sel) sel - l.id else sel + l.id },
                        label = { Text(l.name) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(sel) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ---- preview loading ----
private suspend fun loadPreview(vm: FilesViewModel, file: FileEntry): Preview = withContext(Dispatchers.IO) {
    val m = file.mime?.lowercase().orEmpty()
    val isImage = m.startsWith("image/")
    val isText = m.startsWith("text/") || file.name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "json", "xml", "csv", "log", "kt", "java", "js", "ts", "py", "yaml", "yml")
    if ((!isImage && !isText) || file.size > PREVIEW_MAX_BYTES) return@withContext Preview.None
    val f = vm.downloadToCache(file) ?: return@withContext Preview.None
    when {
        isImage -> runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()?.let { Preview.Img(it.asImageBitmap()) } ?: Preview.None
        else -> runCatching { Preview.Txt(f.readText().take(100_000)) }.getOrDefault(Preview.None)
    }
}

private suspend fun openExternal(vm: FilesViewModel, ctx: android.content.Context, file: FileEntry, onError: (String) -> Unit) {
    val f = vm.downloadToCache(file)
    if (f == null || !DocOpener.openFile(ctx, f, file.mime ?: "*/*")) onError(ctx.getString(R.string.files_open_failed))
}
