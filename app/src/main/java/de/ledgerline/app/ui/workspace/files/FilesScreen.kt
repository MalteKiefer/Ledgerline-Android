package de.ledgerline.app.ui.workspace.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import de.ledgerline.app.ui.common.ConfirmDialog
import de.ledgerline.app.ui.common.TextInputDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.FileEntry
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.ui.common.copyToClipboard
import de.ledgerline.app.ui.common.shareTextChooser
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.RefreshableMessage
import de.ledgerline.app.ui.workspace.common.SearchField
import de.ledgerline.app.ui.workspace.common.TrashBar
import de.ledgerline.app.ui.workspace.common.humanSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(modifier: Modifier = Modifier, vm: FilesViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val viewer by vm.viewer.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val showTrash by vm.showTrash.collectAsStateWithLifecycle()
    val trashCount by vm.trashCount.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val degraded by vm.degraded.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // The file whose bytes are currently rendered (also the export target).
    val ready = viewer as? ViewerState.Ready

    // --- SAF: pick a file to upload ---
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val picked = queryPicked(context, uri)
            vm.uploadPicked(picked.name, picked.mime, picked.size) {
                // A revoked/stale SAF grant yields null; a typed IOException is cleaner than
                // a KotlinNPE and is caught by the upload path → Err(NETWORK) (L5).
                context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open $uri")
            }
        }
    }

    // --- SAF: export the file currently in the viewer ---
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = ready?.file
        if (uri != null && file != null) {
            scope.launch {
                val ok = context.contentResolver.openOutputStream(uri)?.use { os ->
                    vm.exportToStream(os, file)
                } ?: false
                snackbar.showSnackbar(
                    context.getString(if (ok) R.string.file_saved else R.string.file_save_failed),
                )
            }
        }
    }

    // Surface transient VM messages (upload result, save result, etc.) as snackbars.
    // Sentinel tokens are mapped to localized strings; anything else shows verbatim.
    LaunchedEffect(message) {
        message?.let {
            val text = when (it) {
                FilesViewModel.SAVED -> context.getString(R.string.file_saved)
                FilesViewModel.SAVE_FAILED -> context.getString(R.string.file_save_failed)
                else -> it
            }
            snackbar.showSnackbar(text)
            vm.clearMessage()
        }
    }

    // Failed viewer downloads: notify then reset.
    LaunchedEffect(viewer) {
        (viewer as? ViewerState.Failed)?.let {
            snackbar.showSnackbar(it.msg)
            vm.closeViewer()
        }
    }

    // In-app viewer takes over the whole screen when a file's bytes are ready.
    if (ready != null) {
        val saving by vm.saving.collectAsStateWithLifecycle()
        FileViewerScreen(
            file = ready.file,
            bytes = ready.bytes,
            saving = saving,
            onBack = { vm.closeViewer() },
            onExport = { vm.armLockSuppression(); exportLauncher.launch(ready.file.name) },
            onSaveText = { content -> vm.saveText(ready.file, content) },
            modifier = modifier,
        )
        return
    }

    // Dialog state.
    var showNewFolder by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<Pair<String, String>?>(null) } // id, current name
    var renameFile by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteFolderId by remember { mutableStateOf<String?>(null) }
    var deleteFileEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteForeverEntry by remember { mutableStateOf<FileEntry?>(null) }
    var moveTarget by remember { mutableStateOf<FileEntry?>(null) }
    var versionsTarget by remember { mutableStateOf<FileEntry?>(null) }
    var tagsTarget by remember { mutableStateOf<FileEntry?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        // This inner Scaffold is nested inside WorkspaceScaffold, which already
        // applied the top-bar/window insets via the passed [modifier]. Zero the
        // inner content insets so the top inset isn't added twice (a wide gap
        // between the app bar and the first list row).
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!ui.loading && !ui.error && !showTrash) {
                FilesFab(
                    onUpload = { vm.armLockSuppression(); uploadLauncher.launch(arrayOf("*/*")) },
                    onNewFolder = { showNewFolder = true },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading -> LoadingBox()
                ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() })
                else -> Column(Modifier.fillMaxSize()) {
                    if (degraded) de.ledgerline.app.ui.workspace.common.DegradedBanner()
                    // Fixed header: trash bar in trash view, else a "Trash (N)" entry
                    // (only when the trash has something).
                    if (showTrash) {
                        TrashBar(
                            onBack = { vm.setTrash(false) },
                            onEmptyTrash = { confirmEmptyTrash = true },
                            emptyEnabled = ui.files.isNotEmpty(),
                        )
                    } else if (trashCount > 0) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                            TextButton(onClick = { vm.setTrash(true) }) {
                                Icon(Icons.Outlined.Delete, null, Modifier.padding(end = 4.dp).size(18.dp))
                                Text(stringResource(R.string.trash_open, trashCount))
                            }
                        }
                    }
                    if (!showTrash) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SearchField(query = query, onQueryChange = { vm.setQuery(it) }) }
                            SortMenu(current = sort, onSort = { vm.setSort(it) })
                        }
                    }
                    PullToRefreshBox(isRefreshing = ui.loading, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
                        when {
                            showTrash && ui.files.isEmpty() ->
                                RefreshableMessage(stringResource(R.string.trash_empty_state))
                            showTrash -> LazyColumn(Modifier.fillMaxSize()) {
                                items(ui.files, key = { it.id }) { file ->
                                    ListItem(
                                        headlineContent = { Text(file.name) },
                                        supportingContent = { Text(humanSize(file.size)) },
                                        leadingContent = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null) },
                                        trailingContent = {
                                            Row {
                                                IconButton(onClick = { vm.restore(file.id) }) {
                                                    Icon(Icons.Outlined.RestoreFromTrash, stringResource(R.string.action_restore))
                                                }
                                                IconButton(onClick = { deleteForeverEntry = file }) {
                                                    Icon(Icons.Outlined.DeleteForever, stringResource(R.string.action_delete_forever))
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            query.isNotBlank() && ui.folders.isEmpty() && ui.files.isEmpty() ->
                                RefreshableMessage(stringResource(R.string.search_no_results))
                            ui.folders.isEmpty() && ui.files.isEmpty() && !ui.canGoBack ->
                                RefreshableMessage(stringResource(R.string.ws_empty_files))
                            else -> LazyColumn(Modifier.fillMaxSize()) {
                                usage?.let { u ->
                                    item {
                                        val usageText = if (u.quota <= 0) {
                                            stringResource(R.string.file_usage_unlimited, humanSize(u.used))
                                        } else {
                                            stringResource(R.string.file_usage, humanSize(u.used), humanSize(u.quota))
                                        }
                                        Text(
                                            usageText,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                                if (ui.canGoBack) item {
                                    ListItem(
                                        headlineContent = { Text("..") },
                                        leadingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) },
                                        modifier = Modifier.fillMaxWidth().clickable { vm.back() },
                                    )
                                }
                                items(ui.folders, key = { it.id }) { f ->
                                    ListItem(
                                        headlineContent = { Text(f.name) },
                                        leadingContent = { Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                                        trailingContent = {
                                            RowOverflow(
                                                onRename = { renameFolder = f.id to f.name },
                                                onDelete = { deleteFolderId = f.id },
                                                onShare = { vm.openShare(f.id, isFolder = true, f.name, f.share) },
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth().clickable { vm.open(f.id) },
                                    )
                                }
                                items(ui.files, key = { it.id }) { file ->
                                    ListItem(
                                        headlineContent = { Text(file.name) },
                                        supportingContent = { Text(humanSize(file.size)) },
                                        leadingContent = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null) },
                                        trailingContent = {
                                            FileRowOverflow(
                                                file = file,
                                                onRename = { renameFile = file.id to file.name },
                                                onDelete = { deleteFileEntry = file },
                                                onFavorite = { vm.toggleFavorite(file.id) },
                                                onMove = { moveTarget = file },
                                                onVersions = { versionsTarget = file },
                                                onTags = { tagsTarget = file },
                                                onShare = { vm.openShare(file.id, isFolder = false, file.name, file.share) },
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth().clickable { vm.openFile(file) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Busy overlay (upload / download / export in progress).
            if (busy || viewer is ViewerState.Loading) {
                Surface(
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    // --- Dialogs ---
    if (showNewFolder) {
        TextInputDialog(
            title = stringResource(R.string.file_new_folder),
            label = stringResource(R.string.folder_name),
            confirmLabel = stringResource(R.string.action_create),
            initial = "",
            onConfirm = { name -> vm.createFolder(name); showNewFolder = false },
            onDismiss = { showNewFolder = false },
        )
    }
    renameFolder?.let { (id, name) ->
        TextInputDialog(
            title = stringResource(R.string.file_rename),
            label = stringResource(R.string.folder_name),
            confirmLabel = stringResource(R.string.file_rename),
            initial = name,
            onConfirm = { newName -> vm.renameFolder(id, newName); renameFolder = null },
            onDismiss = { renameFolder = null },
        )
    }
    renameFile?.let { (id, name) ->
        TextInputDialog(
            title = stringResource(R.string.file_rename),
            label = stringResource(R.string.file_name),
            confirmLabel = stringResource(R.string.file_rename),
            initial = name,
            onConfirm = { newName -> vm.renameFile(id, newName); renameFile = null },
            onDismiss = { renameFile = null },
        )
    }
    deleteFolderId?.let { id ->
        ConfirmDialog(
            message = stringResource(R.string.file_delete_confirm),
            confirmLabel = stringResource(R.string.file_delete),
            onConfirm = { vm.deleteFolder(id); deleteFolderId = null },
            onDismiss = { deleteFolderId = null },
        )
    }
    deleteFileEntry?.let { file ->
        ConfirmDialog(
            message = stringResource(R.string.file_trash_confirm),
            confirmLabel = stringResource(R.string.file_delete),
            onConfirm = { vm.deleteFile(file); deleteFileEntry = null },
            onDismiss = { deleteFileEntry = null },
        )
    }
    deleteForeverEntry?.let { file ->
        ConfirmDialog(
            message = stringResource(R.string.delete_forever_confirm),
            confirmLabel = stringResource(R.string.action_delete_forever),
            onConfirm = { vm.deleteForever(file); deleteForeverEntry = null },
            onDismiss = { deleteForeverEntry = null },
        )
    }
    moveTarget?.let { file ->
        MoveFileDialog(
            folders = vm.allFolders(),
            current = file.folder,
            onMove = { folderId -> vm.moveFile(file.id, folderId); moveTarget = null },
            onDismiss = { moveTarget = null },
        )
    }
    versionsTarget?.let { file ->
        VersionsSheet(
            file = file,
            onRestore = { ver -> vm.restoreVersion(file.id, ver); versionsTarget = null },
            onDismiss = { versionsTarget = null },
        )
    }
    tagsTarget?.let { file ->
        TagEditDialog(
            initial = file.tags,
            onSave = { tags -> vm.setTags(file.id, tags); tagsTarget = null },
            onDismiss = { tagsTarget = null },
        )
    }

    val shareSheet by vm.shareSheet.collectAsStateWithLifecycle()
    shareSheet?.let { st ->
        de.ledgerline.app.ui.common.ShareLinkSheet(
            state = st,
            onCreate = { vm.createShare(it) },
            onUpdate = { vm.updateShare(it) },
            onRevoke = { vm.revokeShare() },
            onCopy = { link ->
                copyToClipboard(context, link)
                scope.launch { snackbar.showSnackbar(context.getString(R.string.share_copied)) }
            },
            onShareIntent = { link -> context.startActivity(shareTextChooser(context, link)) },
            onDismiss = { vm.closeShare() },
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

@Composable
private fun FilesFab(onUpload: () -> Unit, onNewFolder: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FloatingActionButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.Add, stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.file_upload)) },
                leadingIcon = { Icon(Icons.Outlined.UploadFile, null) },
                onClick = { expanded = false; onUpload() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.file_new_folder)) },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                onClick = { expanded = false; onNewFolder() },
            )
        }
    }
}

/** Overflow for a folder row: rename + delete. */
@Composable
private fun RowOverflow(onRename: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.file_rename)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.share_action)) }, leadingIcon = { Icon(Icons.Outlined.Link, null) }, onClick = { expanded = false; onShare() })
            DropdownMenuItem(text = { Text(stringResource(R.string.file_delete)) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { expanded = false; onDelete() })
        }
    }
}

/** Overflow for a file row: favorite, move, versions, rename, delete. */
@Composable
private fun FileRowOverflow(
    file: FileEntry,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onMove: () -> Unit,
    onVersions: () -> Unit,
    onTags: () -> Unit,
    onShare: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_action)) },
                leadingIcon = { Icon(Icons.Outlined.Link, null) },
                onClick = { expanded = false; onShare() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (file.favorite) R.string.file_unfavorite else R.string.file_favorite)) },
                leadingIcon = { Icon(if (file.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, null) },
                onClick = { expanded = false; onFavorite() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.file_move)) },
                leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) },
                onClick = { expanded = false; onMove() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.pw_tags)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null) },
                onClick = { expanded = false; onTags() },
            )
            if (file.versions.isNotEmpty()) DropdownMenuItem(
                text = { Text(stringResource(R.string.file_versions, file.versions.size)) },
                leadingIcon = { Icon(Icons.Outlined.History, null) },
                onClick = { expanded = false; onVersions() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.file_rename)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = { expanded = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.file_delete)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}

/** Compact sort-order menu (name/date/size, asc/desc). */
@Composable
private fun SortMenu(current: FileSort, onSort: (FileSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.file_sort)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            data class Opt(val s: FileSort, val res: Int)
            listOf(
                Opt(FileSort.NAME_ASC, R.string.file_sort_name_asc),
                Opt(FileSort.NAME_DESC, R.string.file_sort_name_desc),
                Opt(FileSort.DATE_DESC, R.string.file_sort_date_desc),
                Opt(FileSort.DATE_ASC, R.string.file_sort_date_asc),
                Opt(FileSort.SIZE_DESC, R.string.file_sort_size_desc),
                Opt(FileSort.SIZE_ASC, R.string.file_sort_size_asc),
            ).forEach { o ->
                DropdownMenuItem(
                    text = { Text(stringResource(o.res)) },
                    trailingIcon = { if (o.s == current) Icon(Icons.Outlined.Check, null) },
                    onClick = { onSort(o.s); open = false },
                )
            }
        }
    }
}

/** Pick a destination folder (root + the whole file-folder tree) to move a file into. */
@Composable
private fun MoveFileDialog(
    folders: List<NamedFolder>,
    current: String?,
    onMove: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.file_move)) },
        text = {
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.file_move_root)) },
                        leadingContent = { if (current == null) Icon(Icons.Outlined.Check, null) else Icon(Icons.Outlined.Folder, null) },
                        modifier = Modifier.fillMaxWidth().clickable { onMove(null) },
                    )
                }
                items(folders, key = { it.id }) { f ->
                    ListItem(
                        headlineContent = { Text(f.name) },
                        leadingContent = { if (current == f.id) Icon(Icons.Outlined.Check, null) else Icon(Icons.Outlined.Folder, null) },
                        modifier = Modifier.fillMaxWidth().clickable { onMove(f.id) },
                    )
                }
            }
        },
    )
}

/** Saved file versions with restore. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionsSheet(
    file: FileEntry,
    onRestore: (de.ledgerline.app.domain.model.FileVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.file_versions_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            file.versions.forEach { ver ->
                ListItem(
                    headlineContent = { Text(humanSize(ver.size)) },
                    supportingContent = { ver.created?.let { Text(it) } },
                    trailingContent = { TextButton(onClick = { onRestore(ver) }) { Text(stringResource(R.string.file_version_restore)) } },
                )
            }
        }
    }
}

/** Edit a file's tags: chip add (Enter/Done) + remove. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagEditDialog(initial: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val tags = remember { initial.toMutableStateList() }
    var input by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(tags.toList()) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.pw_tags)) },
        text = {
            Column {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    tags.toList().forEach { t ->
                        androidx.compose.material3.InputChip(
                            selected = false, onClick = {}, label = { Text(t) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "remove", modifier = Modifier.size(16.dp).clickable { tags.remove(t) }) },
                        )
                    }
                }
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    label = { Text(stringResource(R.string.pw_tag_add)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (input.isNotBlank()) { tags.add(input.trim()); input = "" } }),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
    )
}
