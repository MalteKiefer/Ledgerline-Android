package de.ledgerline.app.ui.workspace.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.RefreshableMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(modifier: Modifier = Modifier, vm: NotesViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var openId by remember { mutableStateOf<String?>(null) }

    val current = openId
    if (current != null) {
        val note = vm.noteById(current)
        if (note != null) { NoteDetailScreen(note, onBack = { openId = null }, modifier = modifier); return }
    }
    when {
        ui.loading -> LoadingBox(modifier)
        ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() }, modifier)
        ui.notes.isEmpty() -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) { RefreshableMessage(stringResource(R.string.ws_empty_notes)) }
        else -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.notes, key = { it.id }) { note ->
                    ListItem(
                        headlineContent = { Text(note.title.ifBlank { "(untitled)" }) },
                        supportingContent = { Text(note.content.lineSequence().firstOrNull()?.take(80).orEmpty(), maxLines = 1) },
                        trailingContent = { if (note.pinned) Icon(Icons.Outlined.PushPin, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.fillMaxWidth().clickable { openId = note.id },
                    )
                }
            }
        }
    }
}
