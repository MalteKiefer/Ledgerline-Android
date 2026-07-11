package de.ledgerline.app.ui.workspace.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.RefreshableMessage
import de.ledgerline.app.ui.common.ConfirmDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(modifier: Modifier = Modifier, vm: NotesViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var openId by remember { mutableStateOf<String?>(null) }
    // A locally-created blank note being edited before its first save (not yet in the
    // cache). Kept here so the editor can open on it immediately.
    var pendingNew by remember { mutableStateOf<Note?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

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
                onSave = { title, content -> vm.saveNote(note.id, title, content) },
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
            if (!ui.loading && !ui.error) {
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
                ui.notes.isEmpty() -> PullToRefreshBox(ui.loading, { vm.refresh() }) {
                    RefreshableMessage(stringResource(R.string.ws_empty_notes))
                }
                else -> PullToRefreshBox(ui.loading, { vm.refresh() }) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(ui.notes, key = { it.id }) { note ->
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

    deleteTarget?.let { id ->
        ConfirmDialog(
            message = stringResource(R.string.note_delete),
            confirmLabel = stringResource(R.string.note_delete),
            onConfirm = { vm.trashNote(id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
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
            if (note.title.isNotBlank() && preview.isNotBlank()) Text(preview.take(80), maxLines = 1)
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
