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
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.FileEntry
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
                context.contentResolver.openInputStream(uri)!!
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
                FilesViewModel.VERSION_RESTORED -> context.getString(R.string.file_version_restored)
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
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    // Move: either a file id or a folder id is set (not both).
    var moveFileId by remember { mutableStateOf<String?>(null) }
    var moveFolderId by remember { mutableStateOf<String?>(null) }
    // Version history: the file whose versions are shown, and a pending restore confirm.
    var versionsFor by remember { mutableStateOf<FileEntry?>(null) }
    var restoreVersion by remember { mutableStateOf<Pair<FileEntry, de.ledgerline.app.domain.model.FileVersion>?>(null) }

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
                    if (!showTrash) SearchField(query = query, onQueryChange = { vm.setQuery(it) })
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
                                                onMove = { moveFolderId = f.id },
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
                                            RowOverflow(
                                                onRename = { renameFile = file.id to file.name },
                                                onDelete = { deleteFileEntry = file },
                                                onMove = { moveFileId = file.id },
                                                versionCount = file.versions.size,
                                                onVersions = { versionsFor = file },
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
    if (confirmEmptyTrash) {
        ConfirmDialog(
            message = stringResource(R.string.trash_empty_confirm),
            confirmLabel = stringResource(R.string.trash_empty),
            onConfirm = { vm.emptyTrash(); confirmEmptyTrash = false },
            onDismiss = { confirmEmptyTrash = false },
        )
    }

    // Move a file: pick any folder (or root); no exclusions.
    moveFileId?.let { id ->
        MovePickerDialog(
            folders = vm.allFolders(),
            excludeIds = emptySet(),
            onPick = { target -> vm.moveFile(id, target); moveFileId = null },
            onDismiss = { moveFileId = null },
        )
    }
    // Move a folder: exclude itself + its whole subtree so it can't move into itself.
    moveFolderId?.let { id ->
        val folders = vm.allFolders()
        val exclude = remember(id, folders) { subtreeFolderIds(folders, id) }
        MovePickerDialog(
            folders = folders,
            excludeIds = exclude,
            onPick = { target -> vm.moveFolder(id, target); moveFolderId = null },
            onDismiss = { moveFolderId = null },
        )
    }

    // Version history: re-read the entry from the VM so it reflects a just-done restore.
    versionsFor?.let { shown ->
        val live = vm.fileById(shown.id)
        if (live == null || live.versions.isEmpty()) {
            versionsFor = null
        } else {
            VersionsDialog(
                file = live,
                onOpen = { v -> vm.openVersion(live, v); versionsFor = null },
                onRestore = { v -> restoreVersion = live to v },
                onDismiss = { versionsFor = null },
            )
        }
    }
    restoreVersion?.let { (file, version) ->
        ConfirmDialog(
            message = stringResource(R.string.file_version_restore_confirm),
            confirmLabel = stringResource(R.string.file_version_restore),
            onConfirm = {
                vm.restoreVersion(file, version)
                restoreVersion = null
                versionsFor = null
            },
            onDismiss = { restoreVersion = null },
        )
    }
}

/** Folder id [root] plus every descendant folder id (used to exclude a folder's own subtree). */
private fun subtreeFolderIds(
    folders: List<de.ledgerline.app.domain.model.NamedFolder>,
    root: String,
): Set<String> {
    val out = mutableSetOf(root)
    var changed = true
    while (changed) {
        changed = false
        for (f in folders) if (f.parent in out && out.add(f.id)) changed = true
    }
    return out
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

@Composable
private fun RowOverflow(
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: (() -> Unit)? = null,
    versionCount: Int = 0,
    onVersions: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.file_rename)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = { expanded = false; onRename() },
            )
            if (onMove != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.file_move)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) },
                    onClick = { expanded = false; onMove() },
                )
            }
            if (onVersions != null && versionCount > 0) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.file_versions_count, versionCount)) },
                    leadingIcon = { Icon(Icons.Outlined.History, null) },
                    onClick = { expanded = false; onVersions() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.file_delete)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}

/**
 * Folder-picker for a move. Lists "Root" plus every folder indented by depth; [excludeIds]
 * (the folder being moved + its descendants) are hidden so a folder can't be moved into
 * itself. Selecting an entry calls [onPick] with the folder id (null = root).
 */
@Composable
private fun MovePickerDialog(
    folders: List<de.ledgerline.app.domain.model.NamedFolder>,
    excludeIds: Set<String>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val ordered = remember(folders, excludeIds) { orderFoldersByDepth(folders, excludeIds) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.file_move_to)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.folder_root)) },
                        leadingContent = { Icon(Icons.Outlined.Folder, null) },
                        modifier = Modifier.fillMaxWidth().clickable { onPick(null) },
                    )
                }
                items(ordered, key = { it.folder.id }) { row ->
                    ListItem(
                        headlineContent = { Text(row.folder.name) },
                        leadingContent = {
                            Icon(
                                Icons.Outlined.Folder,
                                null,
                                Modifier.padding(start = (row.depth * 16).dp),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().clickable { onPick(row.folder.id) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private data class FolderRow(val folder: de.ledgerline.app.domain.model.NamedFolder, val depth: Int)

/** Depth-first order of [folders] (parent-before-children), skipping [excludeIds] subtrees. */
private fun orderFoldersByDepth(
    folders: List<de.ledgerline.app.domain.model.NamedFolder>,
    excludeIds: Set<String>,
): List<FolderRow> {
    val byParent = folders.groupBy { it.parent }
    val out = mutableListOf<FolderRow>()
    fun walk(parent: String?, depth: Int) {
        byParent[parent].orEmpty()
            .filter { it.id !in excludeIds }
            .sortedBy { it.name.lowercase() }
            .forEach { f ->
                out += FolderRow(f, depth)
                walk(f.id, depth + 1)
            }
    }
    walk(null, 0)
    return out
}

/**
 * Version history for [file]: each row shows the version's date + size with Open (view
 * read-only) and Restore (confirm) actions.
 */
@Composable
private fun VersionsDialog(
    file: FileEntry,
    onOpen: (de.ledgerline.app.domain.model.FileVersion) -> Unit,
    onRestore: (de.ledgerline.app.domain.model.FileVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.file_versions)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(file.versions, key = { it.id }) { v ->
                    ListItem(
                        headlineContent = { Text(formatVersionDate(v.created)) },
                        supportingContent = { Text(humanSize(v.size)) },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { onOpen(v) }) {
                                    Text(stringResource(R.string.file_version_open))
                                }
                                TextButton(onClick = { onRestore(v) }) {
                                    Text(stringResource(R.string.file_version_restore))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** ISO instant → localized date-time; falls back to the raw value (or "—" when absent). */
private fun formatVersionDate(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return try {
        java.time.OffsetDateTime.parse(iso)
            .toLocalDateTime()
            .format(
                java.time.format.DateTimeFormatter.ofLocalizedDateTime(
                    java.time.format.FormatStyle.MEDIUM,
                    java.time.format.FormatStyle.SHORT,
                ),
            )
    } catch (_: Exception) {
        try {
            java.time.Instant.parse(iso)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .format(
                    java.time.format.DateTimeFormatter.ofLocalizedDateTime(
                        java.time.format.FormatStyle.MEDIUM,
                        java.time.format.FormatStyle.SHORT,
                    ),
                )
        } catch (_: Exception) {
            iso
        }
    }
}
