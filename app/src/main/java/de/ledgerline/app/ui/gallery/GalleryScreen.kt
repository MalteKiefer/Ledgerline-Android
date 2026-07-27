package de.ledgerline.app.ui.gallery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.usecase.PhotoSource
import de.ledgerline.app.ui.ops.OpProgressOverlay
import de.ledgerline.app.ui.workspace.common.CenteredMessage
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.humanSize
import kotlinx.coroutines.launch

/** The three top-level gallery views. */
enum class GalleryTab { PHOTOS, ALBUMS, PEOPLE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    modifier: Modifier = Modifier,
    vm: GalleryViewModel = hiltViewModel(),
    albumsVm: AlbumsViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(GalleryTab.PHOTOS) }
    var openAlbumId by remember { mutableStateOf<String?>(null) }
    var openPersonId by remember { mutableStateOf<String?>(null) }
    var showDuplicates by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    var showJobs by remember { mutableStateOf(false) }
    val showTrash by vm.showTrash.collectAsStateWithLifecycle()
    val trashCount by vm.trashCount.collectAsStateWithLifecycle()
    val favoritesOnly by vm.favoritesOnly.collectAsStateWithLifecycle()
    // Photo/video viewer + camera open state is hoisted here so these full-screen
    // views replace the WHOLE gallery (segmented control included) — otherwise the
    // tabs stayed on top, sliding under the status bar.
    var openPhotoId by remember { mutableStateOf<String?>(null) }
    var showCamera by remember { mutableStateOf(false) }

    // Entering the Gallery tab pulls the latest index from the server in the
    // background: cached (offline) photos show immediately, then the fetch updates
    // them. The VM's own init only loads once, so without this a re-visit would show
    // stale/offline data until a manual pull-to-refresh.
    LaunchedEffect(Unit) { vm.refresh() }

    // Duplicate scan — full-screen, hides the tabs.
    if (showDuplicates) {
        DuplicatesScreen(
            modifier = modifier,
            galleryVm = vm,
            onBack = { showDuplicates = false },
        )
        return
    }

    // Full-gallery map — full-screen, hides the tabs.
    if (showMap) {
        GalleryMapScreen(
            vm = vm,
            onOpenPhoto = { openPhotoId = it; showMap = false },
            onBack = { showMap = false },
            modifier = modifier,
        )
        return
    }

    // Trash — full-screen, hides the tabs.
    if (showTrash) {
        GalleryTrashScreen(
            modifier = modifier,
            vm = vm,
            onBack = { vm.setTrash(false) },
        )
        return
    }

    // Jobs / diagnostics sheet — overlays the gallery.
    if (showJobs) {
        GalleryJobsSheet(
            vm = vm,
            onOpenDuplicates = { showJobs = false; showDuplicates = true },
            onDismiss = { showJobs = false },
        )
    }

    // Album detail — full-screen, hides the tabs.
    openAlbumId?.let { id ->
        AlbumDetailScreen(
            albumId = id,
            modifier = modifier,
            galleryVm = vm,
            albumsVm = albumsVm,
            onBack = { openAlbumId = null },
        )
        return
    }

    // Person detail — full-screen, hides the tabs.
    openPersonId?.let { id ->
        PersonDetailScreen(
            personId = id,
            modifier = modifier,
            galleryVm = vm,
            onBack = { openPersonId = null },
        )
        return
    }

    // Camera capture — full-screen, hides the tabs.
    if (showCamera) {
        CameraCaptureScreen(
            onCaptured = { bytes, lat, lng ->
                showCamera = false
                val ts = System.currentTimeMillis()
                vm.uploadAll(
                    listOf(PhotoSource(name = "IMG_$ts.jpg", mime = "image/jpeg", size = bytes.size.toLong(), openInput = { java.io.ByteArrayInputStream(bytes) }, lat = lat, lng = lng))
                )
            },
            onBack = { showCamera = false },
        )
        return
    }

    // Photo/video viewer — full-screen, hides the tabs.
    openPhotoId?.let { id ->
        val photo = vm.photoById(id)
        if (photo != null) {
            PhotoViewerScreen(photo, vm, onBack = { openPhotoId = null }, modifier = modifier)
            return
        } else {
            openPhotoId = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        val degraded by vm.degraded.collectAsStateWithLifecycle()
        if (degraded) de.ledgerline.app.ui.workspace.common.DegradedBanner()
        var overflowOpen by remember { mutableStateOf(false) }
        var searchActive by rememberSaveable { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                SegmentedButton(
                    selected = tab == GalleryTab.PHOTOS,
                    onClick = { tab = GalleryTab.PHOTOS },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                ) { Text(stringResource(R.string.gallery_tab_photos), maxLines = 1) }
                SegmentedButton(
                    selected = tab == GalleryTab.ALBUMS,
                    onClick = { tab = GalleryTab.ALBUMS },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                ) { Text(stringResource(R.string.gallery_tab_albums), maxLines = 1) }
                SegmentedButton(
                    selected = tab == GalleryTab.PEOPLE,
                    onClick = { tab = GalleryTab.PEOPLE },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                ) { Text(stringResource(R.string.gallery_tab_people), maxLines = 1) }
            }
            if (tab == GalleryTab.PHOTOS) {
                IconButton(onClick = {
                    searchActive = !searchActive
                    if (!searchActive) vm.clearSearch()
                }) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = stringResource(R.string.gallery_search_hint),
                        tint = if (searchActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                IconButton(onClick = { vm.toggleFavoritesOnly() }) {
                    Icon(
                        imageVector = if (favoritesOnly) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(R.string.gallery_favorites_only),
                        tint = if (favoritesOnly) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
            }
            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                    )
                }
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.gallery_map)) },
                        onClick = {
                            overflowOpen = false
                            showMap = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.duplicates_action)) },
                        onClick = {
                            overflowOpen = false
                            showDuplicates = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.jobs_action)) },
                        onClick = {
                            overflowOpen = false
                            showJobs = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.trash_open, trashCount)) },
                        onClick = {
                            overflowOpen = false
                            vm.setTrash(true)
                        },
                    )
}
            }
        }

        // Search field appears only when the search icon is toggled — keeps the
        // header a single compact row otherwise.
        if (tab == GalleryTab.PHOTOS && searchActive) {
            GallerySearchField(vm)
        }

        when (tab) {
            GalleryTab.PHOTOS -> PhotosTab(
                vm = vm,
                albumsVm = albumsVm,
                onOpenPhoto = { openPhotoId = it },
                onOpenCamera = { showCamera = true },
                modifier = Modifier.fillMaxSize(),
            )
            GalleryTab.ALBUMS -> AlbumsScreen(
                modifier = Modifier.fillMaxSize(),
                galleryVm = vm,
                albumsVm = albumsVm,
                onOpenAlbum = { openAlbumId = it },
            )
            GalleryTab.PEOPLE -> PeopleScreen(
                modifier = Modifier.fillMaxSize(),
                galleryVm = vm,
                onOpenPerson = { openPersonId = it },
            )
        }
    }
}

/**
 * Semantic photo search field for the Photos tab. Submitting (or the debounce) runs
 * [GalleryViewModel.search]; the clear (×) resets to the normal grouped grid. A leading
 * search icon; the trailing × appears once there's text. Local text state drives the
 * field; the VM owns the results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GallerySearchField(vm: GalleryViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val searching by vm.searching.collectAsStateWithLifecycle()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    OutlinedTextField(
        value = query,
        onValueChange = {
            query = it
            if (it.isBlank()) vm.clearSearch()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.gallery_search_hint)) },
        leadingIcon = {
            if (searching) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.Outlined.Search, contentDescription = null)
            }
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = {
                    query = ""
                    vm.clearSearch()
                    keyboard?.hide()
                }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel))
                }
            }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSearch = {
                vm.search(query)
                keyboard?.hide()
            },
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotosTab(
    vm: GalleryViewModel,
    albumsVm: AlbumsViewModel,
    onOpenPhoto: (String) -> Unit,
    onOpenCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    var fabExpanded by remember { mutableStateOf(false) }

    // Selection mode.
    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var showNewAlbum by remember { mutableStateOf(false) }
    var showAddToAlbum by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showLocationPicker by remember { mutableStateOf(false) }

    fun exitSelection() {
        selectionMode = false
        selected.clear()
    }

    // Location picker is full-screen — replace the whole Photos tab while it's open.
    if (showLocationPicker) {
        val ids = selected.toSet()
        val first = ui.photos.firstOrNull { it.id in ids }
        LocationPickerScreen(
            initialLat = first?.lat,
            initialLng = first?.lng,
            onPick = { lat, lng ->
                vm.setLocation(ids, lat, lng)
                showLocationPicker = false
                exitSelection()
            },
            onBack = { showLocationPicker = false },
            modifier = modifier,
        )
        return
    }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Bulk export: pick a destination folder, then decrypt each selected original and write
    // it there (off the main thread). Plaintext lands only in the user-chosen SAF tree.
    val exportLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            val ids = selected.toList()
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            scope.launch {
                var ok = 0
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ids.forEach { id ->
                        val photo = ui.photos.firstOrNull { it.id == id } ?: return@forEach
                        val bytes = vm.originalBytes(photo) ?: return@forEach
                        val doc = tree?.createFile(
                            photo.mime ?: "application/octet-stream",
                            photo.name?.takeIf { it.isNotBlank() } ?: "photo.jpg",
                        )
                        doc?.uri?.let { u -> context.contentResolver.openOutputStream(u)?.use { it.write(bytes); ok++ } }
                    }
                }
                snackbarHostState.showSnackbar(context.getString(R.string.export_done, ok))
                exitSelection()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val sources = uris.map { uri ->
                PhotoSource(
                    name = queryPhotoName(context, uri),
                    mime = context.contentResolver.getType(uri) ?: "image/jpeg",
                    size = de.ledgerline.app.core.util.blobSize(context.contentResolver, uri),
                    openInput = { context.contentResolver.openInputStream(uri) ?: error("cannot open $uri") },
                )
            }
            vm.uploadAll(sources)
        }
    }

    // Show snackbar for upload_failed messages.
    val failedPrefix = "upload_failed:"
    LaunchedEffect(message) {
        val msg = message ?: return@LaunchedEffect
        if (msg.startsWith(failedPrefix)) {
            val count = msg.removePrefix(failedPrefix).toIntOrNull() ?: 1
            scope.launch {
                val res = snackbarHostState.showSnackbar(
                    message = context.resources.getString(R.string.gallery_upload_failed, count),
                    actionLabel = context.resources.getString(R.string.gallery_upload_retry),
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                )
                if (res == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.retryFailedImports()
            }
            vm.clearMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val results = searchResults
        when {
            // Search active — flat, ranked grid (no day headers), replacing the timeline.
            results != null -> SearchResultsGrid(
                results = results,
                searching = searching,
                vm = vm,
                selectionMode = selectionMode,
                selected = selected,
                onOpenPhoto = onOpenPhoto,
                onToggleSelect = { id ->
                    if (id in selected) selected.remove(id) else selected.add(id)
                },
                onStartSelection = { id ->
                    if (!selectionMode) { selectionMode = true; if (id !in selected) selected.add(id) }
                },
            )
            ui.loading && ui.photos.isEmpty() -> LoadingBox(Modifier.fillMaxSize())
            ui.error -> ErrorBox(
                stringResource(R.string.gallery_error),
                onRetry = { vm.refresh() },
                Modifier.fillMaxSize(),
            )
            ui.photos.isEmpty() -> PullToRefreshBox(
                isRefreshing = ui.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                CenteredMessage(stringResource(R.string.gallery_empty))
            }
            else -> PullToRefreshBox(
                isRefreshing = ui.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    usage?.let { u ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            val usageText = if (u.quota <= 0) {
                                stringResource(R.string.gallery_usage, humanSize(u.used))
                            } else {
                                stringResource(R.string.gallery_usage_full, humanSize(u.used), humanSize(u.quota))
                            }
                            Text(
                                usageText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    ui.dayGroups.forEach { group ->
                        item(
                            span = { GridItemSpan(maxLineSpan) },
                            key = "day-${group.dayKey}",
                        ) {
                            DayHeader(
                                label = group.label,
                                selectionMode = selectionMode,
                                checked = group.photos.isNotEmpty() && group.photos.all { it.id in selected },
                                onToggle = {
                                    if (group.photos.all { it.id in selected }) {
                                        selected.removeAll(group.photos.map { it.id })
                                    } else {
                                        group.photos.forEach { if (it.id !in selected) selected.add(it.id) }
                                    }
                                },
                            )
                        }
                        items(group.photos, key = { it.id }) { photo ->
                            SelectableThumbCell(
                                photo = photo,
                                vm = vm,
                                selectionMode = selectionMode,
                                selected = photo.id in selected,
                                onClick = {
                                    if (selectionMode) {
                                        if (photo.id in selected) selected.remove(photo.id)
                                        else selected.add(photo.id)
                                    } else {
                                        onOpenPhoto(photo.id)
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        if (photo.id !in selected) selected.add(photo.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // Material 3 Expressive FAB menu (upload from picker or take a photo). Hidden while selecting.
        if (!selectionMode) {
            @OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
            FloatingActionButtonMenu(
                modifier = Modifier.align(Alignment.BottomEnd),
                expanded = fabExpanded,
                button = {
                    ToggleFloatingActionButton(
                        checked = fabExpanded,
                        onCheckedChange = { fabExpanded = it },
                    ) {
                        Icon(
                            if (fabExpanded) Icons.Outlined.Close else Icons.Outlined.AddPhotoAlternate,
                            contentDescription = stringResource(R.string.gallery_add),
                        )
                    }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        fabExpanded = false
                        vm.armLockSuppression()
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    },
                    icon = { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null) },
                    text = { Text(stringResource(R.string.gallery_upload_photos)) },
                )
                FloatingActionButtonMenuItem(
                    onClick = { fabExpanded = false; onOpenCamera() },
                    icon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                    text = { Text(stringResource(R.string.gallery_take_photo)) },
                )
            }
        }

        // Selection actions float at the bottom (like the web toolbar) instead of a
        // top bar; the add-photo FAB is hidden while selecting.
        if (selectionMode) {
            SelectionBar(
                count = selected.size,
                onClose = { exitSelection() },
                onFavorite = {
                    // Favorite if any selected photo isn't yet a favorite, else unfavorite all.
                    val ids = selected.toSet()
                    val makeFav = ui.photos.any { it.id in ids && !it.favorite }
                    vm.setFavorite(ids, makeFav)
                    exitSelection()
                },
                onNewAlbum = { showNewAlbum = true },
                onAddToAlbum = { showAddToAlbum = true },
                onDelete = { showDeleteConfirm = true },
                onSetDate = { showDatePicker = true },
                onSetLocation = { showLocationPicker = true },
                onExport = { exportLauncher.launch(null) },
                allSelected = ui.photos.isNotEmpty() && ui.photos.all { it.id in selected },
                onSelectAll = {
                    if (ui.photos.all { it.id in selected }) selected.clear()
                    else ui.photos.forEach { if (it.id !in selected) selected.add(it.id) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }

        // Shared progress overlay (uploads / scans).
        OpProgressOverlay()

        // Snackbar host at bottom.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // New album from selection.
    if (showNewAlbum) {
        TextInputDialog(
            title = stringResource(R.string.album_new),
            confirmLabel = stringResource(R.string.album_create),
            initial = "",
            onConfirm = { name ->
                albumsVm.create(name, selected.toList())
                showNewAlbum = false
                scope.launch { snackbarHostState.showSnackbar(name) }
                exitSelection()
            },
            onDismiss = { showNewAlbum = false },
        )
    }

    // Add selection to an existing album.
    if (showAddToAlbum) {
        AddToAlbumDialog(
            albumsVm = albumsVm,
            onPick = { albumId ->
                albumsVm.addPhotos(albumId, selected.toList())
                showAddToAlbum = false
                exitSelection()
            },
            onDismiss = { showAddToAlbum = false },
        )
    }

    if (showDeleteConfirm) {
        de.ledgerline.app.ui.common.ConfirmDialog(
            message = stringResource(R.string.selection_delete_confirm, selected.size),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                vm.trashPhotos(selected.toSet())
                showDeleteConfirm = false
                exitSelection()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    // Bulk set capture date on the selection.
    if (showDatePicker) {
        val ids = selected.toSet()
        val initial = ui.photos.firstOrNull { it.id in ids }?.let { it.taken_at ?: it.created }
        PhotoDatePickerDialog(
            initialIso = initial,
            onConfirm = { iso ->
                vm.setDate(ids, iso)
                exitSelection()
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

/**
 * Flat, ranked search-results grid (no day grouping) with a small "N results" label —
 * replaces the timeline while a query is active. Shows a spinner+label while searching,
 * "No matches" for an empty result, and keeps selection/thumbnails working.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultsGrid(
    results: List<GalleryPhoto>,
    searching: Boolean,
    vm: GalleryViewModel,
    selectionMode: Boolean,
    selected: List<String>,
    onOpenPhoto: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onStartSelection: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        when {
            searching -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.gallery_searching),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            results.isEmpty() -> Text(
                stringResource(R.string.gallery_search_none),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            else -> Text(
                stringResource(R.string.gallery_search_results, results.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 116.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(results, key = { it.id }) { photo ->
                SelectableThumbCell(
                    photo = photo,
                    vm = vm,
                    selectionMode = selectionMode,
                    selected = photo.id in selected,
                    onClick = {
                        if (selectionMode) onToggleSelect(photo.id) else onOpenPhoto(photo.id)
                    },
                    onLongClick = { onStartSelection(photo.id) },
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onFavorite: () -> Unit,
    onNewAlbum: () -> Unit,
    onAddToAlbum: () -> Unit,
    onDelete: () -> Unit,
    onSetDate: () -> Unit,
    onSetLocation: () -> Unit,
    onExport: () -> Unit,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overflow by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel))
            }
            Text(
                text = stringResource(R.string.selection_count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            IconButton(onClick = onFavorite) {
                Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.action_favorite))
            }
            IconButton(onClick = onNewAlbum) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = stringResource(R.string.album_new))
            }
            IconButton(onClick = onAddToAlbum) {
                Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = stringResource(R.string.album_add_to))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
            }
            Box {
                IconButton(onClick = { overflow = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(if (allSelected) R.string.selection_clear else R.string.selection_select_all))
                        },
                        leadingIcon = {
                            Icon(
                                if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                                contentDescription = null,
                            )
                        },
                        onClick = { overflow = false; onSelectAll() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_download)) },
                        leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                        onClick = { overflow = false; onExport() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_set_date)) },
                        leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
                        onClick = { overflow = false; onSetDate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_set_location)) },
                        leadingIcon = { Icon(Icons.Outlined.Place, contentDescription = null) },
                        onClick = { overflow = false; onSetLocation() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToAlbumDialog(
    albumsVm: AlbumsViewModel,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val albums by albumsVm.albums.collectAsStateWithLifecycle()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.album_add_to)) },
        text = {
            if (albums.isEmpty()) {
                Text(stringResource(R.string.albums_empty))
            } else {
                LazyColumn {
                    items(albums, key = { it.id }) { album ->
                        Text(
                            text = album.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(album.id) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Jobs / diagnostics bottom sheet: library counts + one-tap maintenance actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryJobsSheet(
    vm: GalleryViewModel,
    onOpenDuplicates: () -> Unit,
    onDismiss: () -> Unit,
) {
    val peopleVm: PeopleViewModel = hiltViewModel()
    val people by peopleVm.people.collectAsStateWithLifecycle()
    val failedCount by vm.failedImportCount.collectAsStateWithLifecycle()
    val (images, videos, geo) = remember(people) { vm.diagnostics() }
    val faces = remember(people) { people.sumOf { it.faces.size } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.jobs_action),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            JobStat(stringResource(R.string.jobs_photos), images.toString())
            JobStat(stringResource(R.string.jobs_videos), videos.toString())
            JobStat(stringResource(R.string.jobs_geotagged), geo.toString())
            JobStat(stringResource(R.string.jobs_people), people.size.toString())
            JobStat(stringResource(R.string.jobs_faces), faces.toString())
            if (failedCount > 0) JobStat(stringResource(R.string.jobs_failed), failedCount.toString())
            Spacer(Modifier.size(12.dp))
            OutlinedButton(onClick = { peopleVm.scanFaces(0) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.people_scan_all))
            }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = onOpenDuplicates, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.duplicates_action))
            }
            if (failedCount > 0) {
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { vm.retryFailedImports() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.gallery_upload_retry))
                }
            }
            Spacer(Modifier.size(12.dp))
            Button(
                onClick = { peopleVm.scanFaces(0); vm.retryFailedImports() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.jobs_run_all))
            }
        }
    }
}

@Composable
private fun JobStat(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Full-screen Gallery trash: shows ONLY trashed photos (newest-first) in a
 * selection-style grid. The user multi-selects, then a floating toolbar offers
 * Restore + Delete-forever (confirm). The top bar has back + Empty-trash. Claims
 * its own insets via [LocalFullscreen] so it doesn't slide under the status bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTrashScreen(
    vm: GalleryViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by vm.state.collectAsStateWithLifecycle()

    // Full-screen: hide the outer workspace chrome so this screen owns its insets.
    val fs = de.ledgerline.app.ui.workspace.LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }

    val selected = remember { mutableStateListOf<String>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEmptyConfirm by remember { mutableStateOf(false) }

    // Prune selections that are no longer trashed (restored/purged elsewhere).
    LaunchedEffect(ui.photos) {
        val ids = ui.photos.map { it.id }.toSet()
        selected.retainAll { it in ids }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (ui.photos.isNotEmpty()) {
                        IconButton(onClick = { showEmptyConfirm = true }) {
                            Icon(
                                Icons.Outlined.DeleteForever,
                                contentDescription = stringResource(R.string.trash_empty),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (ui.photos.isEmpty()) {
                CenteredMessage(stringResource(R.string.trash_empty_state))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(ui.photos, key = { it.id }) { photo ->
                        SelectableThumbCell(
                            photo = photo,
                            vm = vm,
                            selectionMode = true,
                            selected = photo.id in selected,
                            onClick = {
                                if (photo.id in selected) selected.remove(photo.id)
                                else selected.add(photo.id)
                            },
                            onLongClick = {},
                        )
                    }
                }
            }

            if (selected.isNotEmpty()) {
                TrashSelectionBar(
                    count = selected.size,
                    onClose = { selected.clear() },
                    onRestore = {
                        vm.restorePhotos(selected.toSet())
                        selected.clear()
                    },
                    onDeleteForever = { showDeleteConfirm = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }

            OpProgressOverlay()
        }
    }

    if (showDeleteConfirm) {
        de.ledgerline.app.ui.common.ConfirmDialog(
            message = stringResource(R.string.delete_forever_confirm),
            confirmLabel = stringResource(R.string.action_delete_forever),
            onConfirm = {
                vm.deleteForever(selected.toSet())
                showDeleteConfirm = false
                selected.clear()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    if (showEmptyConfirm) {
        de.ledgerline.app.ui.common.ConfirmDialog(
            message = stringResource(R.string.trash_empty_confirm),
            confirmLabel = stringResource(R.string.action_delete_forever),
            onConfirm = {
                vm.emptyTrash()
                showEmptyConfirm = false
                selected.clear()
            },
            onDismiss = { showEmptyConfirm = false },
        )
    }
}

@Composable
private fun TrashSelectionBar(
    count: Int,
    onClose: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel))
            }
            Text(
                text = stringResource(R.string.selection_count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            IconButton(onClick = onRestore) {
                Icon(Icons.Outlined.RestoreFromTrash, contentDescription = stringResource(R.string.action_restore))
            }
            IconButton(onClick = onDeleteForever) {
                Icon(Icons.Outlined.DeleteForever, contentDescription = stringResource(R.string.action_delete_forever))
            }
        }
    }
}

/**
 * M3 date picker in a dialog. On confirm it hands back the chosen day formatted as an
 * ISO instant at local start-of-day (day granularity, matching the web's date-only
 * pick). [initialIso] pre-selects the current capture date when parseable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoDatePickerDialog(
    initialIso: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = parseIsoToUtcMillis(initialIso))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) onConfirm(utcMillisToIso(millis))
                    onDismiss()
                },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

/** Best-effort UTC-midnight millis for the day of an ISO/EXIF timestamp; null if unparseable. */
private fun parseIsoToUtcMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    val date = runCatching { java.time.OffsetDateTime.parse(iso).toLocalDate() }
        .recoverCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
        .recoverCatching {
            val norm = iso.trim().replaceFirst(Regex("^(\\d{4}):(\\d{2}):(\\d{2})"), "$1-$2-$3").take(10)
            java.time.LocalDate.parse(norm)
        }
        .getOrNull() ?: return null
    return date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
}

/** The M3 date picker returns UTC-midnight millis for the picked day; format it as an
 *  ISO instant at LOCAL start-of-day so it lands on the intended calendar day. */
private fun utcMillisToIso(millis: Long): String {
    val day = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    return day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toString()
}

/** Resolve DISPLAY_NAME from a content URI; falls back to "photo.jpg". */
private fun queryPhotoName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (ni >= 0 && c.moveToFirst() && !c.isNull(ni)) return c.getString(ni)
    }
    return "photo.jpg"
}

/** Full-span timeline day header shown above each capture-day group in the grid. */
@Composable
private fun DayHeader(
    label: String,
    selectionMode: Boolean = false,
    checked: Boolean = false,
    onToggle: () -> Unit = {},
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (selectionMode) {
            androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ThumbCell(photo: GalleryPhoto, vm: GalleryViewModel, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    SelectableThumbCell(
        photo = photo,
        vm = vm,
        selectionMode = false,
        selected = false,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SelectableThumbCell(
    photo: GalleryPhoto,
    vm: GalleryViewModel,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, photo.id) {
        value = vm.thumb(photo)
    }
    Box(
        Modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        }
        if (photo.media_type == "video") {
            Text(
                "▶",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else if (photo.motionRef != null) {
            // Live/motion photo indicator (still image with an embedded motion clip).
            Icon(
                Icons.Outlined.MotionPhotosOn,
                contentDescription = stringResource(R.string.action_play_motion),
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp),
            )
        }
        if (photo.favorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = stringResource(R.string.action_favorite),
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp),
            )
        }
        if (selectionMode && selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
            )
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
        }
    }
}
