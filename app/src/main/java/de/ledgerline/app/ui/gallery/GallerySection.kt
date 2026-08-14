package de.ledgerline.app.ui.gallery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.gallery.GalleryPhoto
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Gallery tab — Phase 1 (MVP viewing): a capture-date timeline grid with lazy thumbnails, a
 * full-screen lightbox (preview + EXIF + favorite/rotate/delete/save), SAF upload, multi-select bulk
 * delete, and the recycle bin. Albums, sharing, ML/people and device backup are later phases.
 */
@Composable
fun GallerySection(modifier: Modifier = Modifier, vm: GalleryViewModel = hiltViewModel()) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val data by vm.data.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    var lightboxId by remember { mutableStateOf<Int?>(null) }
    var showTrash by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    var showAlbums by remember { mutableStateOf(false) }
    var showPeople by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var addToAlbum by remember { mutableStateOf(false) }
    val selection = remember { androidx.compose.runtime.mutableStateListOf<Int>() }
    var msg by remember { mutableStateOf<String?>(null) }

    val photos = vm.timeline(data)
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Keyset pagination: append the next page as the user nears the end of the loaded rows.
    androidx.compose.runtime.LaunchedEffect(gridState, photos.size) {
        androidx.compose.runtime.snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (photos.isNotEmpty() && last >= photos.size - 24) vm.loadMore() }
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            msg = ctx.getString(R.string.gallery_uploading)
            var ok = 0
            uris.forEach { uri ->
                val tmp = withContext(Dispatchers.IO) {
                    val name = queryName(ctx, uri) ?: "upload"
                    val dir = File(ctx.cacheDir, "uploads").apply { mkdirs() }
                    val dest = File(dir, "gal_${System.nanoTime()}_$name")
                    ctx.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { i.copyTo(it) } }
                    dest to ctx.contentResolver.getType(uri)
                }
                val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
                vm.upload(tmp.first, tmp.first.name, tmp.second) { done.complete(it) }
                if (done.await()) ok++
                runCatching { tmp.first.delete() }
            }
            msg = ctx.getString(R.string.gallery_uploaded_n, ok)
            vm.refresh()
        }
    }

    if (showTrash) { GalleryTrashScreen(vm) { showTrash = false }; return }
    if (showArchive) { GalleryArchiveScreen(vm) { showArchive = false; vm.refresh() }; return }
    if (showAlbums) { GalleryAlbumsScreen(vm) { showAlbums = false }; return }
    if (showPeople) { GalleryPeopleScreen(vm) { showPeople = false }; return }
    if (showBackup) GalleryBackupSheet(vm, onDismiss = { showBackup = false })
    lightboxId?.let { id ->
        val photo = data?.photos?.firstOrNull { it.id == id }
        if (photo != null) { GalleryLightbox(vm, photo, onClose = { lightboxId = null }); return }
        lightboxId = null
    }

    AppScaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = if (selection.isNotEmpty()) selection.size.toString()
                else stringResource(R.string.tab_gallery),
                actions = {
                    if (selection.isNotEmpty()) {
                        IconButton(onClick = { addToAlbum = true }) {
                            Icon(Icons.Outlined.PlaylistAdd, contentDescription = stringResource(R.string.gallery_add_to_album))
                        }
                        IconButton(onClick = {
                            val ids = selection.toList()
                            vm.bulkArchive(ids, true) { ok -> if (ok) { selection.clear(); vm.refresh() } }
                        }) { Icon(Icons.Outlined.Archive, contentDescription = stringResource(R.string.gallery_archive)) }
                        IconButton(onClick = {
                            val ids = selection.toList()
                            vm.bulkDelete(ids) { ok -> if (ok) { selection.clear(); vm.refresh() }; msg = ctx.getString(if (ok) R.string.gallery_deleted else R.string.files_save_failed) }
                        }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
                    } else {
                        IconButton(onClick = { uploadLauncher.launch("image/*") }) {
                            Icon(Icons.Outlined.Upload, contentDescription = stringResource(R.string.gallery_upload_photos))
                        }
                        IconButton(onClick = { showAlbums = true }) {
                            Icon(Icons.Outlined.PhotoAlbum, contentDescription = stringResource(R.string.gallery_albums))
                        }
                        IconButton(onClick = { showPeople = true }) {
                            Icon(Icons.Outlined.People, contentDescription = stringResource(R.string.gallery_people))
                        }
                        IconButton(onClick = { showBackup = true }) {
                            Icon(Icons.Outlined.CloudUpload, contentDescription = stringResource(R.string.gallery_backup))
                        }
                        IconButton(onClick = { showArchive = true }) {
                            Icon(Icons.Outlined.Archive, contentDescription = stringResource(R.string.gallery_archived))
                        }
                        IconButton(onClick = { showTrash = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.files_trash))
                        }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading && photos.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                photos.isEmpty() -> Text(stringResource(R.string.gallery_empty), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Group by capture month so the timeline reads as dated sections (date navigation).
                    photos.groupBy { it.sortKey.take(7) }.forEach { (month, monthPhotos) ->
                        item(key = "h_$month", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(
                                monthLabel(month),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(monthPhotos, key = { it.id }) { p ->
                            GalleryCell(
                                vm = vm, photo = p, selected = p.id in selection,
                                selecting = selection.isNotEmpty(),
                                onClick = { lightboxId = p.id },
                                onLongClick = { if (p.id in selection) selection.remove(p.id) else selection.add(p.id) },
                                onToggleSelect = { if (p.id in selection) selection.remove(p.id) else selection.add(p.id) },
                            )
                        }
                    }
                }
            }
            msg?.let { Text(it, Modifier.align(Alignment.BottomCenter).padding(12.dp), color = MaterialTheme.colorScheme.primary) }
        }
    }

    if (addToAlbum) {
        var albums by remember { mutableStateOf<List<de.ledgerline.app.domain.model.gallery.GalleryAlbum>?>(null) }
        LaunchedEffect(Unit) { albums = vm.albums() }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { addToAlbum = false },
            title = { Text(stringResource(R.string.gallery_add_to_album)) },
            text = {
                val list = albums
                when {
                    list == null -> CircularProgressIndicator()
                    list.isEmpty() -> Text(stringResource(R.string.gallery_albums_empty))
                    else -> Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        list.forEach { a ->
                            Text(
                                a.name,
                                Modifier.fillMaxWidth().clickable {
                                    val ids = selection.toList()
                                    addToAlbum = false
                                    vm.addToAlbum(a.id, ids) { ok -> selection.clear(); msg = ctx.getString(if (ok) R.string.gallery_added_to_album else R.string.files_save_failed) }
                                }.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addToAlbum = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GalleryCell(
    vm: GalleryViewModel,
    photo: GalleryPhoto,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    var bmp by remember(photo.id, photo.version, photo.thumb) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(photo.id, photo.version, photo.thumb) { bmp = vm.thumbnail(photo) }

    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = { if (selecting) onToggleSelect() else onClick() },
                onLongClick = onLongClick,
            ),
    ) {
        val b = bmp
        if (b != null) Image(b, contentDescription = photo.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (photo.status == "processing") CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (photo.isVideo) Icon(
            Icons.Outlined.PlayCircle, contentDescription = null, tint = Color.White,
            modifier = Modifier.align(Alignment.Center).size(28.dp),
        )
        if (photo.favorite) Icon(
            Icons.Outlined.Star, contentDescription = null, tint = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(16.dp),
        )
        if (selecting) Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(RoundedCornerShape(10.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color(0x66000000)),
            contentAlignment = Alignment.Center,
        ) { if (selected) Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall) }
    }
}

/** Camera-roll auto-backup settings: enable (+ permission), Wi-Fi-only, videos, and "back up now". */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun GalleryBackupSheet(vm: GalleryViewModel, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val enabled by vm.backupEnabled.collectAsStateWithLifecycle()
    val wifiOnly by vm.backupWifiOnly.collectAsStateWithLifecycle()
    val videos by vm.backupVideos.collectAsStateWithLifecycle()
    val status by vm.backupStatus.collectAsStateWithLifecycle()
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) vm.setBackupEnabled(true)
    }
    fun enableWithPermission() {
        if (vm.hasMediaPermission()) vm.setBackupEnabled(true)
        else permLauncher.launch(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO))
    }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.gallery_backup), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.gallery_backup_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            BackupRow(stringResource(R.string.gallery_backup_enable), enabled) { on -> if (on) enableWithPermission() else vm.setBackupEnabled(false) }
            BackupRow(stringResource(R.string.gallery_backup_wifi), wifiOnly) { vm.setBackupWifiOnly(it) }
            BackupRow(stringResource(R.string.gallery_backup_videos), videos) { vm.setBackupVideos(it) }

            if (status.running) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { if (status.total > 0) status.done.toFloat() / status.total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Text(stringResource(R.string.gallery_backup_progress, status.done, status.total), style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.material3.TextButton(onClick = {
                if (!vm.hasMediaPermission()) permLauncher.launch(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO))
                else vm.backupNow(includeExisting = false)
            }) { Text(stringResource(R.string.gallery_backup_now)) }
        }
    }
}

@Composable
private fun BackupRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** "YYYY-MM" → a localized month + year label (e.g. "August 2026"); blank input → "—". */
@Composable
private fun monthLabel(ym: String): String {
    if (ym.length < 7) return "—"
    return runCatching {
        val y = ym.substring(0, 4).toInt()
        val m = ym.substring(5, 7).toInt()
        val name = java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        "$name $y"
    }.getOrDefault(ym)
}

/** Resolve a content Uri's display name via the OpenableColumns projection. */
internal fun queryName(ctx: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
