package de.ledgerline.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.files.FileEntry
import de.ledgerline.app.domain.model.files.FileLabel
import de.ledgerline.app.domain.model.files.FilesStats
import de.ledgerline.app.domain.model.files.FilesTrash
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.LedgerRow
import de.ledgerline.app.ui.common.ListBottomPadding
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.common.SoftIconChip
import de.ledgerline.app.ui.common.TextInputDialog
import de.ledgerline.app.ui.common.listSection
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.cardSurface
import kotlinx.coroutines.delay

// ---- Trash ----
@Composable
fun FilesTrashScreen(vm: FilesViewModel, onBack: () -> Unit) {
    var trash by remember { mutableStateOf<FilesTrash?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { trash = vm.loadTrash() }
    val files = trash?.files.orEmpty()
    val folders = trash?.folders.orEmpty()
    AppScaffold(topBar = {
        AppTopBar(title = stringResource(R.string.files_trash), onBack = onBack, actions = {
            if (files.isNotEmpty() || folders.isNotEmpty()) TextButton(onClick = { vm.emptyTrash { reload++ } }) { Text(stringResource(R.string.files_trash_empty_action)) }
        })
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (files.isEmpty() && folders.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.files_trash_is_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                // Trashed folders first (restoring a folder brings back its whole subtree).
                listSection(folders, key = { "tf${it.id}" }) { d ->
                    LedgerRow(
                        title = d.name,
                        subtitle = stringResource(R.string.files_folder),
                        leading = { SoftIconChip(Icons.Outlined.Folder, tint = Brand.tintBlue) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { vm.restoreFolder(d.id) { reload++ } }) { Text(stringResource(R.string.action_restore)) }
                                IconButton(onClick = { vm.forceFolder(d.id) { reload++ } }) {
                                    Icon(Icons.Outlined.DeleteForever, contentDescription = stringResource(R.string.action_delete_forever), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                    )
                }
                listSection(files, key = { "t${it.id}" }) { f ->
                    LedgerRow(
                        title = f.name,
                        subtitle = formatBytes(f.size),
                        leading = { SoftIconChip(Icons.Outlined.Delete, tint = Brand.tintGray) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { vm.restoreFile(f.id) { reload++ } }) { Text(stringResource(R.string.action_restore)) }
                                IconButton(onClick = { vm.forceFile(f.id) { reload++ } }) {
                                    Icon(Icons.Outlined.DeleteForever, contentDescription = stringResource(R.string.action_delete_forever), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---- Search ----
@Composable
fun FilesSearchScreen(vm: FilesViewModel, onOpenDetail: (Int) -> Unit, onBack: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.isBlank()) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true; delay(300); results = vm.search(q); searching = false
    }
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.files_search), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = q, onValueChange = { q = it },
                label = { Text(stringResource(R.string.files_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            when {
                q.isNotBlank() && results.isEmpty() && !searching ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.files_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                    listSection(results, key = { "s${it.id}" }) { f ->
                        val (icon, tint) = iconForFile(f.mime, f.name)
                        LedgerRow(title = f.name, subtitle = formatBytes(f.size), leading = { SoftIconChip(icon, tint = tint) }, onClick = { onOpenDetail(f.id) })
                    }
                }
            }
        }
    }
}

// ---- Stats ----
@Composable
fun FilesStatsScreen(vm: FilesViewModel, onBack: () -> Unit) {
    var stats by remember { mutableStateOf<FilesStats?>(null) }
    val data by vm.data.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { stats = vm.stats() }
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.files_stats), onBack = onBack) }) { pad ->
        val s = stats
        val usage = data?.usage
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().cardSurface()) {
                Text(stringResource(R.string.files_used), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val used = usage?.used ?: s?.used ?: 0
                val quota = usage?.quota
                Text(if (quota != null) "${formatBytes(used)} / ${formatBytes(quota)}" else formatBytes(used), style = MaterialTheme.typography.titleLarge)
                if (quota != null && quota > 0) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { (used.toFloat() / quota.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
            if (!s?.byType.isNullOrEmpty()) {
                SectionLabel(stringResource(R.string.files_stats_by_type))
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s!!.byType.entries.sortedByDescending { it.value }.forEach { (type, bytes) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(type, style = MaterialTheme.typography.bodyMedium)
                            Text(formatBytes(bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            val dupes = s?.duplicates.orEmpty()
            if (dupes.isNotEmpty()) {
                SectionLabel(stringResource(R.string.files_stats_duplicates) + " (${dupes.size})")
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dupes.take(20).forEach { group ->
                        Text(group.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---- Labels management ----
private val LABEL_COLORS = listOf("#7066F5", "#3B9FD6", "#59AD6B", "#E2915A", "#3FAE9F", "#9E70FA", "#6B7280", "#D6455D")

@Composable
fun FilesLabelsScreen(vm: FilesViewModel, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val labels = data?.labels.orEmpty()
    var editing by remember { mutableStateOf<FileLabel?>(null) }
    var creating by remember { mutableStateOf(false) }
    AppScaffold(topBar = {
        AppTopBar(title = stringResource(R.string.files_manage_labels), onBack = onBack, actions = {
            TextButton(onClick = { creating = true }) { Text(stringResource(R.string.action_add)) }
        })
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (labels.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.files_no_labels), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                listSection(labels, key = { "l${it.id}" }) { l ->
                    LedgerRow(
                        title = l.name,
                        leading = { Box(Modifier.size(24.dp).clip(CircleShape).background(parseHex(l.color))) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { editing = l }) { Text(stringResource(R.string.action_edit)) }
                                IconButton(onClick = { vm.deleteLabel(l.id) {} }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
                            }
                        },
                    )
                }
            }
        }
    }

    if (creating) LabelEditDialog(initial = null, onConfirm = { name, color -> creating = false; vm.createLabel(name, color) {} }, onDismiss = { creating = false })
    editing?.let { l -> LabelEditDialog(initial = l, onConfirm = { name, color -> editing = null; vm.updateLabel(l.id, name, color) {} }, onDismiss = { editing = null }) }
}

@Composable
private fun LabelEditDialog(initial: FileLabel?, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: LABEL_COLORS.first()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.files_new_label else R.string.action_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.files_label_name)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LABEL_COLORS.forEach { hex ->
                        val c = parseHex(hex)
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(c)
                                .clickable { color = hex }
                                .padding(2.dp),
                            contentAlignment = Alignment.Center,
                        ) { if (color == hex) Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim(), color) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Parse a "#rrggbb" hex string to a Compose [Color]; falls back to the brand accent. */
internal fun parseHex(hex: String): Color = runCatching {
    val v = hex.removePrefix("#")
    Color(("ff" + v).toLong(16))
}.getOrDefault(Brand.accent)
