package de.ledgerline.app.ui.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.files.FileEntry
import de.ledgerline.app.domain.model.files.FileFolder
import de.ledgerline.app.ui.common.ConfirmDialog
import de.ledgerline.app.ui.common.DocOpener
import de.ledgerline.app.ui.common.LedgerRow
import de.ledgerline.app.ui.common.ListBottomPadding
import de.ledgerline.app.ui.common.RowChevron
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.common.SoftIconChip
import de.ledgerline.app.ui.common.TextInputDialog
import de.ledgerline.app.ui.common.listSection
import de.ledgerline.app.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Files module: a folder browser over the plaintext-relational store. Breadcrumb navigation,
 * grouped folder/file rows (the shared inset-list design language), upload via SAF, open externally
 * through a FileProvider grant, and per-row rename/move/favorite/delete. Embedded as the Files tab
 * in [de.ledgerline.app.ui.shell.AppShell].
 */
@Composable
fun FilesSection(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    vm: FilesViewModel = hiltViewModel(),
) {
    var detailId by remember { mutableStateOf<Int?>(null) }
    var showTrash by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(false) }
    var showShared by remember { mutableStateOf(false) }
    // (kind, id, folderId) for the public-link share dialog.
    var shareTarget by remember { mutableStateOf<Triple<String, Int, Int?>?>(null) }

    // Sub-screens own their top bar; the shell keeps the bottom nav visible, so give them only its
    // clearance at the bottom.
    val bottomOnly = Modifier.padding(bottom = contentPadding.calculateBottomPadding())
    when {
        detailId != null -> { Box(bottomOnly) { FileDetailScreen(vm, detailId!!, onBack = { detailId = null }) }; return }
        showTrash -> { Box(bottomOnly) { FilesTrashScreen(vm) { showTrash = false } }; return }
        showSearch -> { Box(bottomOnly) { FilesSearchScreen(vm, onOpenDetail = { showSearch = false; detailId = it }) { showSearch = false } }; return }
        showStats -> { Box(bottomOnly) { FilesStatsScreen(vm) { showStats = false } }; return }
        showLabels -> { Box(bottomOnly) { FilesLabelsScreen(vm) { showLabels = false } }; return }
        showShared -> { Box(bottomOnly) { SharedWithMeScreen(vm) { showShared = false } }; return }
    }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by vm.data.collectAsStateWithLifecycle()
    val stack by vm.stack.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    var newFolder by remember { mutableStateOf(false) }
    var renameFile by remember { mutableStateOf<FileEntry?>(null) }
    var renameFolder by remember { mutableStateOf<FileFolder?>(null) }
    var deleteFile by remember { mutableStateOf<FileEntry?>(null) }
    var deleteFolder by remember { mutableStateOf<FileFolder?>(null) }
    var moveFile by remember { mutableStateOf<FileEntry?>(null) }
    var moveFolder by remember { mutableStateOf<FileFolder?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    // Optional label filter (null = all).
    var labelFilter by remember { mutableStateOf<Int?>(null) }
    // Multi-select mode for bulk ZIP.
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Int>() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = ctx.getString(R.string.files_uploading)
            val imported = withContext(Dispatchers.IO) { importToTemp(ctx, uri) }
            if (imported == null) { busy = null; return@launch }
            val (file, name, mime) = imported
            vm.upload(file, name, mime) { ok ->
                busy = null
                runCatching { file.delete() }
                if (!ok) busy = ctx.getString(R.string.files_upload_failed)
            }
        }
    }

    val filtering = favoritesOnly || labelFilter != null
    // When filtering (favorites / label), search across ALL folders and hide the folder rows.
    val folders = if (filtering) emptyList() else vm.childFolders(data, vm.currentFolderId)
    val files = if (filtering) {
        data?.files.orEmpty().filter { f ->
            f.deletedAt == null &&
                (!favoritesOnly || f.favorite) &&
                (labelFilter == null || f.labels.any { it.id == labelFilter })
        }.sortedByDescending { it.updatedAt ?: "" }
    } else vm.filesIn(data, vm.currentFolderId)

    // Only bottom-nav clearance here; FilesTopBar (a TopAppBar) applies the top status-bar inset itself,
    // so applying the full contentPadding too would double the top gap above the title.
    Box(Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding())) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    onClose = { selecting = false; selected.clear() },
                    onZip = {
                        if (selected.isNotEmpty()) scope.launch {
                            busy = ctx.getString(R.string.files_downloading)
                            val dir = java.io.File(ctx.cacheDir, "docs").apply { mkdirs() }
                            val dest = java.io.File(dir, "selection.zip")
                            val ok = vm.zipSelection(selected.toList(), dest)
                            busy = null
                            if (ok) DocOpener.openFile(ctx, dest, "application/zip") else busy = ctx.getString(R.string.files_open_failed)
                            selecting = false; selected.clear()
                        }
                    },
                )
            } else
            FilesTopBar(
                favoritesOnly = favoritesOnly,
                onToggleFavorites = { favoritesOnly = !favoritesOnly; if (favoritesOnly) labelFilter = null },
                onSelect = { selecting = true },
                onSearch = { showSearch = true }, onTrash = { showTrash = true }, onStats = { showStats = true },
                onLabels = { showLabels = true }, onShared = { showShared = true },
                onDownloadFolder = vm.currentFolderId?.let { fid ->
                    {
                        scope.launch {
                            busy = ctx.getString(R.string.files_downloading)
                            val dir = java.io.File(ctx.cacheDir, "docs").apply { mkdirs() }
                            val dest = java.io.File(dir, "folder_$fid.zip")
                            val ok = vm.zipFolder(fid, dest)
                            busy = null
                            if (ok) DocOpener.openFile(ctx, dest, "application/zip") else busy = ctx.getString(R.string.files_open_failed)
                        }
                    }
                },
            )
            if (!favoritesOnly) Breadcrumb(stack, onRoot = { vm.goToRoot() }, onCrumb = { vm.goTo(it) })

            // Label filter chips (from the account's labels).
            val labels = data?.labels.orEmpty()
            if (labels.isNotEmpty() && !favoritesOnly) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    labels.forEach { l ->
                        androidx.compose.material3.FilterChip(
                            selected = labelFilter == l.id,
                            onClick = { labelFilter = if (labelFilter == l.id) null else l.id },
                            label = { Text(l.name) },
                        )
                    }
                }
            }

            val refreshing by vm.refreshing.collectAsStateWithLifecycle()
            de.ledgerline.app.ui.common.RefreshBox(refreshing = refreshing, onRefresh = { vm.pullRefresh() }) {
            when {
                loading && data == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                folders.isEmpty() && files.isEmpty() ->
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.files_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                    if (folders.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.files_section_folders)) }
                        listSection(folders, key = { "d${it.id}" }) { folder ->
                            FolderRow(
                                folder = folder,
                                onOpen = { vm.openFolder(folder) },
                                onShare = { shareTarget = Triple("folder", folder.id, folder.id) },
                                onRename = { renameFolder = folder },
                                onMove = { moveFolder = folder },
                                onDelete = { deleteFolder = folder },
                            )
                        }
                    }
                    if (files.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.files_section_files)) }
                        listSection(files, key = { "f${it.id}" }) { file ->
                            val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file.id, file.version) {
                                value = vm.thumbnail(file)
                            }
                            FileRow(
                                file = file,
                                thumb = thumb,
                                selecting = selecting,
                                checked = file.id in selected,
                                onToggleSelect = { if (file.id in selected) selected.remove(file.id) else selected.add(file.id) },
                                onDetail = { detailId = file.id },
                                onOpenExternal = {
                                    scope.launch {
                                        busy = ctx.getString(R.string.files_downloading)
                                        val f = vm.downloadToCache(file)
                                        busy = null
                                        if (f == null || !DocOpener.openFile(ctx, f, file.mime ?: "*/*")) {
                                            busy = ctx.getString(R.string.files_open_failed)
                                        }
                                    }
                                },
                                onShare = { shareTarget = Triple("file", file.id, null) },
                                onRename = { renameFile = file },
                                onMove = { moveFile = file },
                                onToggleFavorite = { vm.toggleFavorite(file.id, !file.favorite) {} },
                                onDelete = { deleteFile = file },
                            )
                        }
                    }
                }
            }
            }
        }

        // Upload / new-folder FAB (a small two-action menu).
        FabMenu(
            onNewFolder = { newFolder = true },
            onUpload = { picker.launch("*/*") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )

        busy?.let { msg ->
            Text(
                msg,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
            )
        }
    }

    // ---- dialogs ----
    shareTarget?.let { (kind, id, fid) ->
        ShareDialog(vm, kind = kind, id = id, folderId = fid, onDismiss = { shareTarget = null })
    }
    if (newFolder) TextInputDialog(
        title = stringResource(R.string.files_new_folder), label = stringResource(R.string.files_folder_name),
        confirmLabel = stringResource(R.string.action_create), initial = "",
        onConfirm = { name -> newFolder = false; vm.createFolder(name) {} }, onDismiss = { newFolder = false },
    )
    renameFile?.let { f ->
        TextInputDialog(
            title = stringResource(R.string.action_rename), label = stringResource(R.string.files_folder_name),
            confirmLabel = stringResource(R.string.action_save), initial = f.name,
            onConfirm = { name -> renameFile = null; vm.renameFile(f.id, name) {} }, onDismiss = { renameFile = null },
        )
    }
    renameFolder?.let { d ->
        TextInputDialog(
            title = stringResource(R.string.action_rename), label = stringResource(R.string.files_folder_name),
            confirmLabel = stringResource(R.string.action_save), initial = d.name,
            onConfirm = { name -> renameFolder = null; vm.renameFolder(d.id, name) {} }, onDismiss = { renameFolder = null },
        )
    }
    deleteFile?.let { f ->
        ConfirmDialog(
            message = stringResource(R.string.files_delete_confirm), confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { deleteFile = null; vm.deleteFile(f.id) {} }, onDismiss = { deleteFile = null },
        )
    }
    deleteFolder?.let { d ->
        ConfirmDialog(
            message = stringResource(R.string.files_delete_folder_confirm), confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { deleteFolder = null; vm.deleteFolder(d.id) {} }, onDismiss = { deleteFolder = null },
        )
    }
    moveFile?.let { f ->
        MoveDialog(
            folders = data?.folders.orEmpty().filter { it.deletedAt == null },
            currentParent = f.folderId,
            onPick = { dest -> moveFile = null; vm.moveFile(f.id, dest) {} },
            onDismiss = { moveFile = null },
        )
    }
    moveFolder?.let { d ->
        MoveDialog(
            folders = data?.folders.orEmpty().filter { it.deletedAt == null && it.id != d.id },
            currentParent = d.parentId,
            onPick = { dest -> moveFolder = null; vm.moveFolder(d.id, dest) {} },
            onDismiss = { moveFolder = null },
        )
    }
}

@Composable
private fun Breadcrumb(stack: List<FileFolder>, onRoot: () -> Unit, onCrumb: (FileFolder) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Root is a home icon (the "Files" title already sits in the top bar — no duplicate label).
        Icon(
            Icons.Outlined.Home,
            contentDescription = stringResource(R.string.files_root),
            tint = if (stack.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onRoot).padding(4.dp).height(20.dp),
        )
        stack.forEachIndexed { i, folder ->
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Crumb(folder.name, onClick = { onCrumb(folder) }, active = i == stack.lastIndex)
        }
    }
}

@Composable
private fun Crumb(text: String, onClick: () -> Unit, active: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun FolderRow(folder: FileFolder, onOpen: () -> Unit, onShare: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    LedgerRow(
        title = folder.name,
        leading = { SoftIconChip(Icons.Outlined.Folder, tint = Brand.tintBlue) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowChevron()
                RowMenu(
                    items = listOf(
                        MenuItem(stringResource(R.string.action_share), onShare),
                        MenuItem(stringResource(R.string.action_rename), onRename),
                        MenuItem(stringResource(R.string.action_move), onMove),
                        MenuItem(stringResource(R.string.action_delete), onDelete, destructive = true),
                    ),
                )
            }
        },
        onClick = onOpen,
    )
}

@Composable
private fun FileRow(
    file: FileEntry,
    thumb: androidx.compose.ui.graphics.ImageBitmap? = null,
    selecting: Boolean = false,
    checked: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onDetail: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    val (icon, tint) = iconForFile(file.mime, file.name)
    LedgerRow(
        title = file.name,
        subtitle = formatBytes(file.size),
        leading = {
            when {
                selecting -> androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = { onToggleSelect() })
                thumb != null -> androidx.compose.foundation.Image(
                    thumb, contentDescription = null,
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(Brand.chipRadius)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                else -> SoftIconChip(icon, tint = tint)
            }
        },
        trailing = {
            if (!selecting) Row(verticalAlignment = Alignment.CenterVertically) {
                if (file.favorite) Icon(Icons.Outlined.Star, contentDescription = null, tint = Brand.accent, modifier = Modifier.height(18.dp))
                RowMenu(
                    items = listOf(
                        MenuItem(stringResource(R.string.action_open), onOpenExternal),
                        MenuItem(stringResource(R.string.action_share), onShare),
                        MenuItem(stringResource(R.string.action_rename), onRename),
                        MenuItem(stringResource(R.string.action_move), onMove),
                        MenuItem(stringResource(if (file.favorite) R.string.action_unfavorite else R.string.action_favorite), onToggleFavorite),
                        MenuItem(stringResource(R.string.action_delete), onDelete, destructive = true),
                    ),
                )
            }
        },
        onClick = if (selecting) onToggleSelect else onDetail,
    )
}

@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onZip: () -> Unit) {
    de.ledgerline.app.ui.common.AppTopBar(
        title = "$count",
        onBack = onClose,
        actions = {
            androidx.compose.material3.IconButton(onClick = onZip) { Icon(Icons.Outlined.FolderZip, contentDescription = stringResource(R.string.files_download_zip)) }
        },
    )
}

@Composable
private fun FilesTopBar(
    favoritesOnly: Boolean,
    onToggleFavorites: () -> Unit,
    onSelect: () -> Unit,
    onSearch: () -> Unit,
    onTrash: () -> Unit,
    onStats: () -> Unit,
    onLabels: () -> Unit,
    onShared: () -> Unit,
    onDownloadFolder: (() -> Unit)?,
) {
    var overflow by remember { mutableStateOf(false) }
    de.ledgerline.app.ui.common.AppTopBar(
        title = stringResource(if (favoritesOnly) R.string.files_favorites else R.string.files_root),
        actions = {
            androidx.compose.material3.IconButton(onClick = onToggleFavorites) {
                Icon(if (favoritesOnly) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = stringResource(R.string.files_favorites), tint = if (favoritesOnly) Brand.accent else androidx.compose.material3.LocalContentColor.current)
            }
            androidx.compose.material3.IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.files_search)) }
            androidx.compose.material3.IconButton(onClick = onTrash) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.files_trash)) }
            Box {
                androidx.compose.material3.IconButton(onClick = { overflow = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.files_select)) }, onClick = { overflow = false; onSelect() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.shared_with_me)) }, onClick = { overflow = false; onShared() })
                    onDownloadFolder?.let { dl ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.files_download_zip)) }, onClick = { overflow = false; dl() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.files_stats)) }, onClick = { overflow = false; onStats() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.files_manage_labels)) }, onClick = { overflow = false; onLabels() })
                }
            }
        },
    )
}

private data class MenuItem(val label: String, val onClick: () -> Unit, val destructive: Boolean = false)

@Composable
private fun RowMenu(items: List<MenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(item.label, color = if (item.destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
                    },
                    onClick = { open = false; item.onClick() },
                )
            }
        }
    }
}

@Composable
private fun FabMenu(onNewFolder: () -> Unit, onUpload: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ExtendedFloatingActionButton(
            onClick = { open = true },
            icon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
            text = { Text(stringResource(R.string.action_add)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_new_folder)) },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                onClick = { open = false; onNewFolder() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_upload)) },
                leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                onClick = { open = false; onUpload() },
            )
        }
    }
}

@Composable
internal fun MoveDialog(folders: List<FileFolder>, currentParent: Int?, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_move_title)) },
        text = {
            LazyColumn {
                item {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_root)) },
                        enabled = currentParent != null,
                        onClick = { onPick(null) },
                    )
                }
                items(folders.size) { i ->
                    val f = folders[i]
                    DropdownMenuItem(
                        text = { Text(f.name) },
                        enabled = f.id != currentParent,
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        onClick = { onPick(f.id) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ---- helpers ----

/** Pick an icon + brand tint for a file by MIME (falling back to the filename extension). */
internal fun iconForFile(mime: String?, name: String): Pair<ImageVector, Color> {
    val m = mime?.lowercase() ?: ""
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        m.startsWith("image/") -> Icons.Outlined.Image to Brand.tintGreen
        m.startsWith("video/") -> Icons.Outlined.Movie to Brand.tintViolet
        m.startsWith("audio/") -> Icons.Outlined.Audiotrack to Brand.tintOrange
        m == "application/pdf" || ext == "pdf" -> Icons.Outlined.PictureAsPdf to Brand.tintOrange
        m.startsWith("text/") || ext in setOf("txt", "md", "json", "xml", "csv", "log") -> Icons.Outlined.Article to Brand.tintTeal
        ext in setOf("zip", "gz", "tar", "7z", "rar") -> Icons.Outlined.FolderZip to Brand.tintGray
        else -> Icons.Outlined.InsertDriveFile to Brand.tintGray
    }
}

/** Copy a SAF [uri] into a private cache file; returns (file, displayName, mime) or null. */
private fun importToTemp(ctx: Context, uri: Uri): Triple<File, String, String?>? = runCatching {
    val name = queryDisplayName(ctx, uri) ?: "upload_${System.currentTimeMillis()}"
    val mime = ctx.contentResolver.getType(uri)
    val dir = File(ctx.cacheDir, "uploads").apply { mkdirs() }
    val dest = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    ctx.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return null
    Triple(dest, name, mime)
}.getOrNull()

private fun queryDisplayName(ctx: Context, uri: Uri): String? =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
