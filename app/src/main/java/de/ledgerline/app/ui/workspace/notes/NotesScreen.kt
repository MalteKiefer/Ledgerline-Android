package de.ledgerline.app.ui.workspace.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.RefreshableMessage
import de.ledgerline.app.ui.workspace.common.SearchField
import de.ledgerline.app.ui.workspace.common.TagChips
import de.ledgerline.app.ui.workspace.common.TagFilterRow
import de.ledgerline.app.ui.workspace.common.TrashBar
import de.ledgerline.app.ui.common.ConfirmDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(modifier: Modifier = Modifier, vm: NotesViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val showTrash by vm.showTrash.collectAsStateWithLifecycle()
    val trashCount by vm.trashCount.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val allTags by vm.allTags.collectAsStateWithLifecycle()
    val activeTag by vm.activeTag.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var openId by remember { mutableStateOf<String?>(null) }
    // A locally-created blank note being edited before its first save (not yet in the
    // cache). Kept here so the editor can open on it immediately.
    var pendingNew by remember { mutableStateOf<Note?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var deleteForeverTarget by remember { mutableStateOf<String?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    // --- Editor / detail takes over the whole screen ---
    val current = openId
    if (current != null) {
        val note = vm.noteById(current) ?: pendingNew?.takeIf { it.id == current }
        if (note != null) {
            NoteDetailScreen(
                note = note,
                onSave = { title, content, tags -> vm.saveNote(note.id, title, content, tags) },
                onTogglePin = { vm.togglePin(note.id) },
                onDelete = { vm.trashNote(note.id); openId = null; pendingNew = null },
                onBack = { openId = null; pendingNew = null },
                modifier = modifier,
            )
            return
        } else {
            openId = null
            pendingNew = null
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!ui.loading && !ui.error && !showTrash) {
                FloatingActionButton(onClick = { pendingNew = vm.newBlankNote().also { openId = it.id } }) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.note_new))
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading -> LoadingBox()
                ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() })
                else -> Column(Modifier.fillMaxSize()) {
                    // Fixed header above the list — trash bar in trash view, otherwise a
                    // "Trash (N)" entry (only when there's something in the trash).
                    if (showTrash) {
                        TrashBar(
                            onBack = { vm.setTrash(false) },
                            onEmptyTrash = { confirmEmptyTrash = true },
                            emptyEnabled = ui.notes.isNotEmpty(),
                        )
                    } else if (trashCount > 0) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                            TextButton(onClick = { vm.setTrash(true) }) {
                                Icon(Icons.Outlined.DeleteOutline, null, Modifier.padding(end = 4.dp).size(18.dp))
                                Text(stringResource(R.string.trash_open, trashCount))
                            }
                        }
                    }
                    if (!showTrash) SearchField(query = query, onQueryChange = { vm.setQuery(it) })
                    if (!showTrash) {
                        TagFilterRow(
                            tags = allTags,
                            activeTag = activeTag,
                            onSelect = { vm.setActiveTag(it) },
                        )
                    }
                    val emptyText = if (showTrash) R.string.trash_empty_state
                    else if (query.isNotBlank()) R.string.search_no_results
                    else R.string.ws_empty_notes
                    PullToRefreshBox(ui.loading, { vm.refresh() }, Modifier.weight(1f)) {
                        if (ui.notes.isEmpty()) {
                            RefreshableMessage(stringResource(emptyText))
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(ui.notes, key = { it.id }) { note ->
                                    if (showTrash) {
                                        TrashNoteRow(
                                            note = note,
                                            onRestore = { vm.restore(note.id) },
                                            onDeleteForever = { deleteForeverTarget = note.id },
                                        )
                                    } else {
                                        NoteRow(
                                            note = note,
                                            onOpen = { openId = note.id },
                                            onDelete = { deleteTarget = note.id },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { id ->
        ConfirmDialog(
            message = stringResource(R.string.note_delete),
            confirmLabel = stringResource(R.string.note_delete),
            onConfirm = { vm.trashNote(id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }

    deleteForeverTarget?.let { id ->
        ConfirmDialog(
            message = stringResource(R.string.delete_forever_confirm),
            confirmLabel = stringResource(R.string.action_delete_forever),
            onConfirm = { vm.deleteForever(id); deleteForeverTarget = null },
            onDismiss = { deleteForeverTarget = null },
        )
    }

    if (confirmEmptyTrash) {
        ConfirmDialog(
            message = stringResource(R.string.trash_empty_confirm),
            confirmLabel = stringResource(R.string.trash_empty),
            onConfirm = { vm.emptyTrash(); confirmEmptyTrash = false },
            onDismiss = { confirmEmptyTrash = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashNoteRow(note: Note, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    val untitled = stringResource(R.string.note_untitled)
    ListItem(
        headlineContent = { Text(noteRowTitle(note, untitled)) },
        supportingContent = if (note.tags.isNotEmpty()) {
            { TagChips(note.tags) }
        } else null,
        trailingContent = {
            Row {
                IconButton(onClick = onRestore) {
                    Icon(Icons.Outlined.RestoreFromTrash, stringResource(R.string.action_restore))
                }
                IconButton(onClick = onDeleteForever) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete_forever))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The row title: the note title, else its first non-empty content line, else "Untitled". */
private fun noteRowTitle(note: Note, untitled: String): String {
    if (note.title.isNotBlank()) return note.title
    val firstLine = note.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    return firstLine?.take(80) ?: untitled
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteRow(note: Note, onOpen: () -> Unit, onDelete: () -> Unit) {
    val untitled = stringResource(R.string.note_untitled)
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(noteRowTitle(note, untitled)) },
        supportingContent = {
            val preview = note.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val showPreview = note.title.isNotBlank() && preview.isNotBlank()
            if (showPreview || note.tags.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showPreview) Text(preview.take(80), maxLines = 1)
                    TagChips(note.tags)
                }
            }
        },
        leadingContent = if (note.pinned) {
            { Icon(Icons.Outlined.PushPin, null, tint = MaterialTheme.colorScheme.primary) }
        } else null,
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.note_delete))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.note_delete)) },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    )
}
