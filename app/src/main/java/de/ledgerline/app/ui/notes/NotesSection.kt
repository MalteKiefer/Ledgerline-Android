@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.ledgerline.app.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.notes.NoteRow
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.cardSurface

/** Internal URI scheme used to route a tapped [[wikilink]] back to a note id. */
private const val WIKI_SCHEME = "ll-wiki:"
private val WIKILINK_RE = Regex("""\[\[([^\]\[]+)]]""")

/** Rewrite `[[Title]]` → a Markdown link `[Title](ll-wiki:Title)` so the renderer makes it tappable. */
private fun rewriteWikilinks(md: String): String =
    WIKILINK_RE.replace(md) { m ->
        val target = m.groupValues[1].trim()
        "[$target]($WIKI_SCHEME$target)"
    }

/** The Notes tab: folder filter + search + note list (pin/favorite) with a quick-add/edit sheet + trash. */
@Composable
fun NotesSection(modifier: Modifier = Modifier, vm: NotesViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.bootstrap() }
    val folders by vm.folders.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val selectedFolder by vm.selectedFolder.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()

    var editorOpen by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<Int?>(null) }
    var searching by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var manageFolders by remember { mutableStateOf(false) }

    if (showTrash) { NotesTrashScreen(vm) { showTrash = false }; return }

    val shown = when {
        query.isNotBlank() -> results
        selectedFolder != null -> notes.filter { it.folderId == selectedFolder }
        else -> notes
    }

    AppScaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.tab_notes),
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.clearSearch() }) {
                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.notes_search))
                    }
                    IconButton(onClick = { manageFolders = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = stringResource(R.string.notes_folders))
                    }
                    IconButton(onClick = { showTrash = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.files_trash))
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (searching) {
                OutlinedTextField(
                    value = query, onValueChange = { vm.search(it) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    label = { Text(stringResource(R.string.notes_search)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                )
            } else if (folders.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selectedFolder == null, { vm.selectFolder(null) }, label = { Text(stringResource(R.string.notes_all)) })
                    folders.forEach { f ->
                        FilterChip(selectedFolder == f.id, { vm.selectFolder(f.id) }, label = { Text(f.name) })
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.notes_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(shown, key = { it.id }) { n ->
                            NoteCard(n, onOpen = { editId = n.id; editorOpen = true }, onFav = { vm.setFavorite(n.id, !n.favorite) }, onPin = { vm.setPinned(n.id, !n.pinned) })
                        }
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = { editId = null; editorOpen = true },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.notes_new)) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }
        }
    }

    if (editorOpen) {
        NoteEditorSheet(
            vm, existingId = editId, defaultFolder = selectedFolder,
            rows = notes,
            onOpenNote = { targetId -> editId = targetId },
            onDismiss = { editorOpen = false },
        )
    }
    if (manageFolders) {
        NotesFoldersSheet(vm, onDismiss = { manageFolders = false })
    }
}

@Composable
private fun NoteCard(n: NoteRow, onOpen: () -> Unit, onFav: () -> Unit, onPin: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onOpen() }.cardSurface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n.title.ifBlank { stringResource(R.string.notes_untitled) }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onPin) { Icon(Icons.Outlined.PushPin, contentDescription = stringResource(R.string.notes_pin), tint = if (n.pinned) Brand.accent else MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onFav) { Icon(Icons.Outlined.Star, contentDescription = stringResource(R.string.notes_favorite), tint = if (n.favorite) Brand.tintOrange else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (n.tags.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                n.tags.forEach { t -> Text("#$t", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun NoteEditorSheet(
    vm: NotesViewModel,
    existingId: Int?,
    defaultFolder: Int?,
    rows: List<NoteRow>,
    onOpenNote: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val folders by vm.folders.collectAsStateWithLifecycle()

    var loaded by remember(existingId) { mutableStateOf(existingId == null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    val tags = remember { mutableStateListOf<String>() }
    var folderId by remember { mutableStateOf(defaultFolder) }
    var version by remember { mutableIntStateOf(0) }
    var backlinks by remember { mutableStateOf<List<de.ledgerline.app.domain.model.notes.NoteBacklink>>(emptyList()) }
    var preview by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Fetch the full body (+ backlinks) for an existing note.
    LaunchedEffect(existingId) {
        if (existingId != null) {
            vm.openNote(existingId)?.let { note ->
                title = note.title; body = note.body; folderId = note.folderId; version = note.version
                tags.clear(); tags.addAll(note.tags)
                backlinks = note.backlinks
            }
            loaded = true
        }
    }

    // Resolve a [[wikilink]] title to an existing note id (case-insensitive), or null if none yet.
    fun resolveWikilink(target: String): Int? =
        rows.firstOrNull { it.title.equals(target.trim(), ignoreCase = true) }?.id

    // Compose UriHandler that intercepts our rewritten wikilink scheme and opens the target note.
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val wikiHandler = remember(rows) {
        object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith(WIKI_SCHEME)) {
                    resolveWikilink(uri.removePrefix(WIKI_SCHEME))?.let(onOpenNote)
                } else runCatching { uriHandler.openUri(uri) }
            }
        }
    }

    fun save() {
        if (busy) return
        busy = true
        vm.save(existingId, title, body, tags.toList(), folderId, version) { ok -> busy = false; if (ok) onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!loaded) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (existingId == null) R.string.notes_new else R.string.notes_edit), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (existingId != null) IconButton(onClick = { vm.delete(existingId) { onDismiss() } }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                }
                IconButton(onClick = { preview = !preview }) {
                    Icon(if (preview) Icons.Outlined.Edit else Icons.Outlined.Visibility, contentDescription = stringResource(if (preview) R.string.notes_edit else R.string.notes_preview))
                }
            }

            OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.notes_title)) }, singleLine = true)

            if (preview) {
                // Rewrite [[Title]] into a tappable internal link before rendering; the wikiHandler
                // resolves the title to a note id and re-targets the editor.
                val rendered = remember(body) { rewriteWikilinks(body) }.ifBlank { stringResource(R.string.notes_empty_body) }
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides wikiHandler) {
                    Box(Modifier.fillMaxWidth().cardSurface()) { Markdown(content = rendered) }
                }
            } else {
                OutlinedTextField(value = body, onValueChange = { body = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.notes_body_md)) }, minLines = 6)
            }

            // Folder picker chips.
            if (folders.isNotEmpty()) {
                SectionLabel(stringResource(R.string.notes_folder))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(folderId == null, { folderId = null }, label = { Text(stringResource(R.string.notes_no_folder)) })
                    folders.forEach { f -> FilterChip(folderId == f.id, { folderId = f.id }, label = { Text(f.name) }) }
                }
            }

            // Tags.
            SectionLabel(stringResource(R.string.notes_tags))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { t -> InputChip(selected = false, onClick = { tags.remove(t) }, label = { Text(t) }, trailingIcon = { Icon(Icons.Outlined.Delete, null, Modifier.width(16.dp)) }) }
            }
            OutlinedTextField(
                value = tagInput, onValueChange = { tagInput = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.notes_tag_add)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { val t = tagInput.trim(); if (t.isNotEmpty() && t !in tags) tags.add(t); tagInput = "" }),
            )

            // Backlinks — notes that link here via [[wikilink]]. Tap to open.
            if (backlinks.isNotEmpty()) {
                SectionLabel(stringResource(R.string.notes_backlinks))
                backlinks.forEach { bl ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenNote(bl.id) }.cardSurface(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(bl.title.ifBlank { stringResource(R.string.notes_untitled) }, style = MaterialTheme.typography.bodyLarge)
                        if (bl.snippet.isNotBlank()) Text(bl.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledIconButton(
                    onClick = { save() }, enabled = !busy && (title.isNotBlank() || body.isNotBlank()),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Brand.accent, contentColor = Color.White),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_save)) }
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun NotesFoldersSheet(vm: NotesViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val folders by vm.folders.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(16.dp).imePadding().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(stringResource(R.string.notes_folders))
            folders.forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { vm.deleteFolder(f.id) }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error) }
                }
            }
            OutlinedTextField(value = newName, onValueChange = { newName = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.notes_folder_name)) }, singleLine = true)
            TextButton(enabled = newName.isNotBlank(), onClick = { vm.addFolder(newName.trim(), null) { newName = "" } }) { Text(stringResource(R.string.notes_add_folder)) }
        }
    }
}

@Composable
private fun NotesTrashScreen(vm: NotesViewModel, onBack: () -> Unit) {
    var trash by remember { mutableStateOf<de.ledgerline.app.domain.model.notes.NotesTrash?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { trash = vm.loadTrash() }
    val notes = trash?.notes.orEmpty()
    val folders = trash?.folders.orEmpty()

    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.files_trash), onBack = onBack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (notes.isEmpty() && folders.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.files_trash_is_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(folders, key = { "nf${it.id}" }) { f ->
                    Row(Modifier.fillMaxWidth().cardSurface(), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { vm.restoreFolder(f.id) { reload++ } }) { Text(stringResource(R.string.action_restore)) }
                    }
                }
                items(notes, key = { "nn${it.id}" }) { n ->
                    Row(Modifier.fillMaxWidth().cardSurface(), verticalAlignment = Alignment.CenterVertically) {
                        Text(n.title.ifBlank { stringResource(R.string.notes_untitled) }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { vm.restore(n.id) { reload++ } }) { Text(stringResource(R.string.action_restore)) }
                        IconButton(onClick = { vm.force(n.id) { reload++ } }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete_forever), tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
