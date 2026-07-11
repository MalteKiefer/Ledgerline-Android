package de.ledgerline.app.ui.workspace.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.TextButton
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
import de.ledgerline.app.ui.common.openUrl
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.RefreshableMessage
import de.ledgerline.app.ui.workspace.common.SearchField
import de.ledgerline.app.ui.workspace.common.TagChips
import de.ledgerline.app.ui.workspace.common.TagFilterRow
import de.ledgerline.app.ui.workspace.common.TrashBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(modifier: Modifier = Modifier, vm: BookmarksViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val activeFolder by vm.activeFolder.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val showTrash by vm.showTrash.collectAsStateWithLifecycle()
    val trashCount by vm.trashCount.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val allTags by vm.allTags.collectAsStateWithLifecycle()
    val activeTag by vm.activeTag.collectAsStateWithLifecycle()
    val bookmarkView by vm.bookmarkView.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val linkChooser by vm.linkChooserEnabled.collectAsStateWithLifecycle()

    // The bookmark being edited; null id = create new. Null = editor closed.
    var editorFor by remember { mutableStateOf<EditorTarget?>(null) }
    var deleteForeverTarget by remember { mutableStateOf<String?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    fun open(url: String) = openUrl(context, url, linkChooser)

    editorFor?.let { target ->
        val existing = target.id?.let { vm.bookmarkById(it) }
        BookmarkEditorDialog(
            initial = existing ?: Bookmark(folderId = activeFolder),
            folders = folders,
            onSave = { url, title, description, folderId, tags ->
                if (target.id == null) vm.addBookmark(url, title, description, folderId, tags)
                else vm.editBookmark(target.id, url, title, description, folderId, tags)
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
            if (!ui.loading && !ui.error && !showTrash) {
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
                else -> Column(Modifier.fillMaxSize()) {
                    // Fixed header above the list — a LazyColumn can't host the chip
                    // row / empty-state as items (they'd be measured with infinite height).
                    if (showTrash) {
                        TrashBar(
                            onBack = { vm.setTrash(false) },
                            onEmptyTrash = { confirmEmptyTrash = true },
                            emptyEnabled = ui.items.isNotEmpty(),
                        )
                    } else {
                        BookmarkViewChips(
                            view = bookmarkView,
                            onSelectView = { vm.setView(it) },
                        )
                        BookmarksToolbar(
                            folders = folders,
                            activeFolder = activeFolder,
                            trashCount = trashCount,
                            onSelectFolder = { vm.setActiveFolder(it) },
                            onAddFolder = { vm.addFolder(it) },
                            onRenameFolder = { id, name -> vm.renameFolder(id, name) },
                            onDeleteFolder = { vm.deleteFolder(it) },
                            onOpenTrash = { vm.setTrash(true) },
                        )
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
                    else R.string.ws_empty_bookmarks
                    PullToRefreshBox(ui.loading, { vm.refresh() }, Modifier.weight(1f)) {
                        if (ui.items.isEmpty()) {
                            RefreshableMessage(stringResource(emptyText))
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(ui.items, key = { it.id }) { bookmark ->
                                    if (showTrash) {
                                        TrashBookmarkRow(
                                            bookmark = bookmark,
                                            onRestore = { vm.restore(bookmark.id) },
                                            onDeleteForever = { deleteForeverTarget = bookmark.id },
                                        )
                                    } else {
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

/** Which bookmark the editor is editing; a null [id] means "create new". */
private data class EditorTarget(val id: String?)

/** A compact chip row selecting the active view: All / Favorites / Read-later. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkViewChips(
    view: BookmarkView,
    onSelectView: (BookmarkView) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = view == BookmarkView.ALL,
            onClick = { onSelectView(BookmarkView.ALL) },
            label = { Text(stringResource(R.string.bm_view_all)) },
        )
        FilterChip(
            selected = view == BookmarkView.FAVORITES,
            onClick = { onSelectView(BookmarkView.FAVORITES) },
            leadingIcon = { Icon(Icons.Outlined.Star, null) },
            label = { Text(stringResource(R.string.bm_view_favorites)) },
        )
        FilterChip(
            selected = view == BookmarkView.READ_LATER,
            onClick = { onSelectView(BookmarkView.READ_LATER) },
            leadingIcon = { Icon(Icons.Outlined.Bookmark, null) },
            label = { Text(stringResource(R.string.bm_view_readlater)) },
        )
    }
}

/** Folder filter chips + a "manage folders" overflow (add/rename/delete). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksToolbar(
    folders: List<NamedFolder>,
    activeFolder: String?,
    trashCount: Int,
    onSelectFolder: (String?) -> Unit,
    onAddFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onOpenTrash: () -> Unit,
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
                if (trashCount > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.trash_open, trashCount)) },
                        onClick = { manageExpanded = false; onOpenTrash() },
                    )
                }
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    hostOf(bookmark.url),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TagChips(bookmark.tags)
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashBookmarkRow(
    bookmark: Bookmark,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
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
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRestore) {
                    Icon(Icons.Outlined.RestoreFromTrash, stringResource(R.string.action_restore))
                }
                IconButton(onClick = onDeleteForever) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete_forever))
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Best-effort display host (scheme/path stripped) for a bookmark's url. */
private fun hostOf(url: String): String =
    runCatching { url.toUri().host ?: url }.getOrDefault(url)
