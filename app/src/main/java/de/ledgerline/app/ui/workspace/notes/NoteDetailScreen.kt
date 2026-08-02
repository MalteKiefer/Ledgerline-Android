package de.ledgerline.app.ui.workspace.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import de.ledgerline.app.R
import de.ledgerline.app.core.Dates
import de.ledgerline.app.data.DateFormatPref
import de.ledgerline.app.domain.model.Note
import de.ledgerline.app.domain.workspace.Tags
import de.ledgerline.app.ui.workspace.LocalFullscreen
import de.ledgerline.app.ui.workspace.common.TagChips
import de.ledgerline.app.ui.common.ConfirmDialog

/**
 * Inline editor for a single note: an editable title + a multiline plain-text body
 * (markdown is stored verbatim, no rich renderer this phase). Saves via the top-bar
 * Save action and on back (when title/content changed). Also offers pin/unpin and delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    note: Note,
    dateFormat: DateFormatPref = DateFormatPref.SYSTEM,
    onSave: (title: String, content: String, tags: List<String>) -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }

    var title by rememberSaveable(note.id) { mutableStateOf(note.title) }
    var content by rememberSaveable(note.id) { mutableStateOf(note.content) }
    var tags by remember(note.id) { mutableStateOf(note.tags) }
    var tagDraft by rememberSaveable(note.id) { mutableStateOf("") }

    // Keep the latest editor values available to the back handler without re-registering it.
    val latestTitle by rememberUpdatedState(title)
    val latestContent by rememberUpdatedState(content)
    val latestTags by rememberUpdatedState(tags)
    val latestDraft by rememberUpdatedState(tagDraft)
    // Returns true when an actual change was persisted (drives the "saved" confirmation).
    val save: () -> Boolean = {
        val finalTags = Tags.mergeDraft(latestTags, latestDraft)
        val changed = latestTitle != note.title || latestContent != note.content || finalTags != note.tags
        if (changed) onSave(latestTitle, latestContent, finalTags)
        changed
    }

    BackHandler { save(); onBack() }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val savedMsg = stringResource(R.string.note_saved)

    var confirmDelete by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val titleFocus = remember { FocusRequester() }
    val isNew = note.title.isBlank() && note.content.isBlank()

    // Brand-new blank notes open in edit; existing notes open in the rendered preview.
    var preview by rememberSaveable(note.id) { mutableStateOf(!isNew) }

    // Brand-new note → land the cursor in the title.
    LaunchedEffect(note.id) {
        if (isNew) { titleFocus.requestFocus(); keyboard?.show() }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_notes)) },
                navigationIcon = {
                    IconButton(onClick = { save(); onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { preview = !preview }) {
                        if (preview) {
                            Icon(Icons.Outlined.Edit, stringResource(R.string.note_edit_mode))
                        } else {
                            Icon(Icons.Outlined.Visibility, stringResource(R.string.note_preview_mode))
                        }
                    }
                    IconButton(onClick = onTogglePin) {
                        Icon(
                            Icons.Outlined.PushPin,
                            stringResource(if (note.pinned) R.string.note_unpin else R.string.note_pin),
                            tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.note_delete))
                    }
                    IconButton(onClick = {
                        val saved = save()
                        keyboard?.hide()
                        preview = true            // leave edit mode on save
                        if (saved) scope.launch { snackbar.showSnackbar(savedMsg) }
                    }) {
                        Icon(Icons.Outlined.Check, stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (preview) {
                if (title.isNotBlank()) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                if (note.tags.isNotEmpty()) {
                    TagChips(note.tags)
                }
                MarkdownText(content, modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.note_title_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                )
                de.ledgerline.app.ui.workspace.common.TagInput(
                    tags = tags,
                    onTagsChange = { tags = it },
                    draft = tagDraft,
                    onDraftChange = { tagDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.note_body_hint)) },
                    minLines = 10,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val updated = note.updated?.let { Dates.format(it, dateFormat) }.orEmpty()
            if (updated.isNotBlank()) {
                Text(
                    stringResource(R.string.note_updated, updated),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            message = stringResource(R.string.note_delete),
            confirmLabel = stringResource(R.string.note_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
