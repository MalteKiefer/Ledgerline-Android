package de.ledgerline.app.ui.workspace.bookmarks

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.Bookmark
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.ui.common.ConfirmDialog
import de.ledgerline.app.ui.common.TextInputDialog
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.RefreshableMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(modifier: Modifier = Modifier, vm: BookmarksViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val activeFolder by vm.activeFolder.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // The bookmark being edited; null id = create new. Null = editor closed.
    var editorFor by remember { mutableStateOf<EditorTarget?>(null) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    fun open(url: String) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        }
    }

    editorFor?.let { target ->
        val existing = target.id?.let { vm.bookmarkById(it) }
        BookmarkEditorDialog(
            initial = existing ?: Bookmark(folderId = activeFolder),
            folders = folders,
            onSave = { url, title, description, folderId ->
                if (target.id == null) vm.addBookmark(url, title, description, folderId)
                else vm.editBookmark(target.id, url, title, description, folderId)
                editorFor = null
            },
            onCreateFolder = { vm.addFolder(it) },
            onDismiss = { editorFor = null },
        )
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!ui.loading && !ui.error) {
                FloatingActionButton(onClick = { editorFor = EditorTarget(null) }) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.bm_new))
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading -> LoadingBox()
                ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() })
                else -> PullToRefreshBox(ui.loading, { vm.refresh() }) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            BookmarksToolbar(
                                folders = folders,
                                activeFolder = activeFolder,
                                onSelectFolder = { vm.setActiveFolder(it) },
                                onAddFolder = { vm.addFolder(it) },
                                onRenameFolder = { id, name -> vm.renameFolder(id, name) },
                                onDeleteFolder = { vm.deleteFolder(it) },
                            )
                        }
                        if (ui.items.isEmpty()) {
                            item { RefreshableMessage(stringResource(R.string.ws_empty_bookmarks)) }
                        } else {
                            items(ui.items, key = { it.id }) { bookmark ->
                                BookmarkRow(
                                    bookmark = bookmark,
                                    onOpen = { open(bookmark.url) },
                                    onToggleFavorite = { vm.toggleFavorite(bookmark.id) },
                                    onToggleReadLater = { vm.toggleReadLater(bookmark.id) },
                                    onEdit = { editorFor = EditorTarget(bookmark.id) },
                                    onDelete = { vm.trashBookmark(bookmark.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Which bookmark the editor is editing; a null [id] means "create new". */
private data class EditorTarget(val id: String?)

/** Folder filter chips + a "manage folders" overflow (add/rename/delete). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksToolbar(
    folders: List<NamedFolder>,
    activeFolder: String?,
    onSelectFolder: (String?) -> Unit,
    onAddFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    var manageExpanded by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = activeFolder == null,
                onClick = { onSelectFolder(null) },
                label = { Text(stringResource(R.string.bm_all_folders)) },
            )
            folders.forEach { f ->
                FilterChip(
                    selected = activeFolder == f.id,
                    onClick = { onSelectFolder(f.id) },
                    label = { Text(f.name) },
                )
            }
        }

        Box {
            IconButton(onClick = { manageExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, stringResource(R.string.bm_manage_folders))
            }
            DropdownMenu(expanded = manageExpanded, onDismissRequest = { manageExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bm_new_folder)) },
                    onClick = { manageExpanded = false; showNewFolder = true },
                )
                folders.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.folder_rename) + ": " + f.name) },
                        onClick = { manageExpanded = false; renameTarget = f.id to f.name },
                    )
                }
                folders.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.folder_delete) + ": " + f.name) },
                        onClick = { manageExpanded = false; deleteTarget = f.id },
                    )
                }
            }
        }
    }

    if (showNewFolder) {
        TextInputDialog(
            title = stringResource(R.string.bm_new_folder),
            label = stringResource(R.string.bm_folder),
            confirmLabel = stringResource(R.string.action_create),
            initial = "",
            onConfirm = { onAddFolder(it); showNewFolder = false },
            onDismiss = { showNewFolder = false },
        )
    }
    renameTarget?.let { (id, name) ->
        TextInputDialog(
            title = stringResource(R.string.folder_rename),
            label = stringResource(R.string.bm_folder),
            confirmLabel = stringResource(R.string.action_save),
            initial = name,
            onConfirm = { onRenameFolder(id, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { id ->
        ConfirmDialog(
            message = stringResource(R.string.folder_delete),
            confirmLabel = stringResource(R.string.folder_delete),
            onConfirm = { onDeleteFolder(id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleReadLater: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        headlineContent = { Text(bookmark.title.ifBlank { bookmark.url }, maxLines = 1) },
        supportingContent = {
            Text(
                hostOf(bookmark.url),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            IconButton(onClick = onToggleFavorite) {
                if (bookmark.favorite) {
                    Icon(Icons.Outlined.Star, stringResource(R.string.bm_favorite), tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Outlined.StarBorder, stringResource(R.string.bm_favorite))
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleReadLater) {
                    if (bookmark.readLater) {
                        Icon(Icons.Outlined.Bookmark, stringResource(R.string.bm_read_later), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(Icons.Outlined.BookmarkBorder, stringResource(R.string.bm_read_later))
                    }
                }
                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.bm_edit))
                    }
                    DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bm_edit)) },
                            onClick = { overflowExpanded = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bm_delete)) },
                            onClick = { overflowExpanded = false; confirmDelete = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bm_favorite)) },
                            onClick = { overflowExpanded = false; onToggleFavorite() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bm_read_later)) },
                            onClick = { overflowExpanded = false; onToggleReadLater() },
                        )
                    }
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onOpen),
    )

    if (confirmDelete) {
        ConfirmDialog(
            message = stringResource(R.string.bm_delete),
            confirmLabel = stringResource(R.string.bm_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Best-effort display host (scheme/path stripped) for a bookmark's url. */
private fun hostOf(url: String): String =
    runCatching { url.toUri().host ?: url }.getOrDefault(url)
