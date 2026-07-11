package de.ledgerline.app.ui.workspace.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.Bookmark
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.ui.common.TextInputDialog

/**
 * Add/edit dialog for a bookmark. Used for both create (pass a blank [initial]) and
 * edit (pass a prefilled one). [onSave] receives the raw field values (BookmarkOps
 * trims them). The folder picker offers "No folder", the existing folders, and an
 * inline "New folder…" prompt via [onCreateFolder]. Save is disabled while the url
 * is blank.
 */
@Composable
fun BookmarkEditorDialog(
    initial: Bookmark,
    folders: List<NamedFolder>,
    onSave: (url: String, title: String, description: String, folderId: String?) -> Unit,
    onCreateFolder: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initial.url) }
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var description by rememberSaveable { mutableStateOf(initial.description) }
    var folderId by rememberSaveable { mutableStateOf(initial.folderId) }
    var showNewFolder by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.id.isBlank()) R.string.bm_new else R.string.bm_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.bm_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.bm_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.bm_desc_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                val folderName = folders.firstOrNull { it.id == folderId }?.name
                    ?: stringResource(R.string.bm_no_folder)
                FolderPickerField(label = stringResource(R.string.bm_folder), value = folderName) { dismiss ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bm_no_folder)) },
                        onClick = { folderId = null; dismiss() },
                    )
                    folders.forEach { f ->
                        DropdownMenuItem(text = { Text(f.name) }, onClick = { folderId = f.id; dismiss() })
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bm_new_folder)) },
                        onClick = { dismiss(); showNewFolder = true },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = { onSave(url, title, description, folderId) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (showNewFolder) {
        TextInputDialog(
            title = stringResource(R.string.bm_new_folder),
            label = stringResource(R.string.bm_folder),
            confirmLabel = stringResource(R.string.action_create),
            initial = "",
            onConfirm = { name -> onCreateFolder(name); showNewFolder = false },
            onDismiss = { showNewFolder = false },
        )
    }
}

/** A read-only text-field-styled trigger that opens a dropdown of [menu] items. */
@Composable
private fun FolderPickerField(
    label: String,
    value: String,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent overlay to capture taps (a disabled TextField swallows clicks).
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menu { expanded = false }
        }
    }
}
