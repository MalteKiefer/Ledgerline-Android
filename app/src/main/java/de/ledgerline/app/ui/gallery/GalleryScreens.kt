package de.ledgerline.app.ui.gallery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayCircleFilled
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.gallery.GalleryExif
import de.ledgerline.app.domain.model.gallery.GalleryPhoto
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen single-photo viewer with the light EXIF sheet and per-photo actions. */
@Composable
fun GalleryLightbox(vm: GalleryViewModel, photo: GalleryPhoto, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var bmp by remember(photo.id, photo.version) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var loadFailed by remember(photo.id, photo.version) { mutableStateOf(false) }
    var showExif by remember { mutableStateOf(false) }
    var exif by remember(photo.id) { mutableStateOf<GalleryExif?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var addAlbum by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var videoFile by remember(photo.id) { mutableStateOf<File?>(null) }
    var preparing by remember(photo.id) { mutableStateOf(false) }

    // Still preview only for images; a video shows a poster + play button (fetched on demand).
    LaunchedEffect(photo.id, photo.version) {
        if (!photo.isVideo) { bmp = vm.preview(photo); loadFailed = bmp == null }
    }

    fun playVideo(motion: Boolean) {
        if (preparing) return
        preparing = true
        scope.launch {
            val f = vm.videoToCache(photo, motion)
            preparing = false
            if (f != null) videoFile = f else msg = ctx.getString(R.string.gallery_video_failed)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(photo.mime ?: "image/jpeg"),
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = ctx.contentResolver.openOutputStream(uri)?.use { withContext(Dispatchers.IO) { vm.saveTo(photo, it) } } ?: false
            msg = ctx.getString(if (ok) R.string.files_saved else R.string.files_save_failed)
        }
    }

    AppScaffold(topBar = {
        AppTopBar(title = photo.name, onBack = onClose, actions = {
            IconButton(onClick = { vm.setFavorite(photo.id, !photo.favorite) }) {
                Icon(if (photo.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = stringResource(R.string.action_favorite))
            }
            IconButton(onClick = { showExif = true; scope.launch { exif = vm.exif(photo.id) } }) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.files_info))
            }
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                androidx.compose.material3.DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.gallery_add_to_album)) }, onClick = { showMenu = false; addAlbum = true })
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.gallery_rotate)) }, onClick = { showMenu = false; vm.rotate(photo.id, photo.rotation) })
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.files_save_device)) }, onClick = { showMenu = false; saveLauncher.launch(photo.name) })
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.gallery_archive)) }, onClick = { showMenu = false; vm.archive(photo.id, true) { onClose() } })
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { showMenu = false; confirmDelete = true })
                }
            }
        })
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(Color.Black), contentAlignment = Alignment.Center) {
            val vf = videoFile
            val b = bmp
            when {
                vf != null -> VideoPlayer(vf, Modifier.fillMaxSize())
                photo.isVideo -> {
                    // Poster + play button (download the web MP4 on demand, then play inline).
                    IconButton(onClick = { playVideo(false) }, modifier = Modifier.size(72.dp)) {
                        if (preparing) CircularProgressIndicator(color = Color.White)
                        else Icon(Icons.Outlined.PlayCircleFilled, contentDescription = stringResource(R.string.gallery_play), tint = Color.White, modifier = Modifier.fillMaxSize())
                    }
                }
                b != null -> Image(b, contentDescription = photo.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                loadFailed -> Text(stringResource(R.string.gallery_preview_pending), color = Color.White, modifier = Modifier.padding(24.dp))
                else -> CircularProgressIndicator(color = Color.White)
            }
            // Live Photo: a still with an attached motion clip → play it on demand.
            if (photo.motion && !photo.isVideo && vf == null) {
                androidx.compose.material3.AssistChip(
                    onClick = { playVideo(true) },
                    label = { Text(stringResource(R.string.gallery_live)) },
                    leadingIcon = { if (preparing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.MotionPhotosOn, contentDescription = null) },
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                )
            }
            msg?.let { Text(it, Modifier.align(Alignment.BottomCenter).padding(12.dp), color = MaterialTheme.colorScheme.primary) }
        }
    }

    if (showExif) ExifSheet(photo, exif, onEditDate = { iso -> vm.setTakenAt(photo.id, iso) }, onDismiss = { showExif = false })
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.action_delete)) },
        text = { Text(stringResource(R.string.gallery_delete_confirm)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(photo.id) { onClose() } }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (addAlbum) {
        var albums by remember { mutableStateOf<List<de.ledgerline.app.domain.model.gallery.GalleryAlbum>?>(null) }
        LaunchedEffect(Unit) { albums = vm.albums() }
        AlertDialog(
            onDismissRequest = { addAlbum = false },
            title = { Text(stringResource(R.string.gallery_add_to_album)) },
            text = {
                when (val list = albums) {
                    null -> CircularProgressIndicator()
                    else -> if (list.isEmpty()) Text(stringResource(R.string.gallery_albums_empty))
                    else Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        list.forEach { a ->
                            Text(
                                a.name,
                                Modifier.fillMaxWidth().clickable {
                                    addAlbum = false
                                    vm.addToAlbum(a.id, listOf(photo.id)) { ok -> msg = ctx.getString(if (ok) R.string.gallery_added_to_album else R.string.files_save_failed) }
                                }.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addAlbum = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ExifSheet(photo: GalleryPhoto, exif: GalleryExif?, onEditDate: (String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var editDate by remember { mutableStateOf(false) }
    val lat = exif?.lat ?: photo.lat?.toDouble()
    val lng = exif?.lng ?: photo.lng?.toDouble()
    // Server-provided place, else reverse-geocode the GPS with the on-device Geocoder (no network dep).
    var geoPlace by remember(photo.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(lat, lng, exif?.place, photo.place) {
        if ((exif?.place ?: photo.place) == null && lat != null && lng != null) geoPlace = reverseGeocode(ctx, lat, lng)
    }
    val place = exif?.place ?: photo.place ?: geoPlace
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(photo.name, style = MaterialTheme.typography.titleMedium)
            InfoLine(stringResource(R.string.files_size), formatBytesG(photo.size))
            photo.width?.let { w -> photo.height?.let { h -> InfoLine(stringResource(R.string.gallery_dimensions), "$w × $h") } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gallery_taken), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { editDate = true }) {
                    Text((exif?.takenAt ?: photo.takenAt)?.take(10) ?: stringResource(R.string.gallery_edit_date))
                }
            }
            (exif?.camera ?: photo.camera)?.let { InfoLine(stringResource(R.string.gallery_camera), it) }
            // Location: an embedded OSM mini-map (osmdroid) + place name.
            if (lat != null && lng != null) GalleryMiniMap(lat, lng, onOpen = { openInMaps(ctx, lat, lng, place) })
            if (place != null || (lat != null && lng != null)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp)
                        .then(if (lat != null && lng != null) Modifier.clickable { openInMaps(ctx, lat, lng, place) } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(place ?: stringResource(R.string.gallery_place), style = MaterialTheme.typography.bodyMedium)
                        if (lat != null && lng != null) Text(stringResource(R.string.gallery_open_map), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            exif?.exif?.forEach { (section, tags) ->
                Text(section, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                tags.forEach { (k, v) -> InfoLine(k, v) }
            }
        }
    }

    if (editDate) {
        val state = androidx.compose.material3.rememberDatePickerState()
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { editDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        onEditDate(d.toString() + "T12:00:00")
                    }
                    editDate = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { androidx.compose.material3.DatePicker(state = state) }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
    }
}

/** Trashed photos: a thumbnail grid with per-photo restore / permanent delete + empty-trash. */
@Composable
fun GalleryTrashScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<GalleryPhoto>?>(null) }
    fun reload() { scope.launch { rows = vm.loadTrash() } }
    LaunchedEffect(Unit) { reload() }

    AppScaffold(topBar = {
        AppTopBar(title = stringResource(R.string.files_trash), onBack = onBack, actions = {
            TextButton(onClick = { vm.emptyTrash { reload() } }) { Text(stringResource(R.string.files_trash_empty_action)) }
        })
    }) { pad ->
        val list = rows
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text(stringResource(R.string.gallery_trash_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.fillMaxSize().padding(pad).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(list, key = { it.id }) { p ->
                    var bmp by remember(p.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                    LaunchedEffect(p.id) { bmp = vm.thumbnail(p) }
                    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        bmp?.let { Image(it, contentDescription = p.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x88000000)), horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconButton(onClick = { vm.restore(p.id) { reload() } }) { Icon(Icons.Outlined.Restore, contentDescription = stringResource(R.string.action_restore), tint = Color.White) }
                            IconButton(onClick = { vm.force(p.id) { reload() } }) { Icon(Icons.Outlined.DeleteForever, contentDescription = stringResource(R.string.action_delete), tint = Color.White) }
                        }
                    }
                }
            }
        }
    }
}

/** Archived photos: a thumbnail grid with per-photo unarchive. Hidden from the main timeline. */
@Composable
fun GalleryArchiveScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<GalleryPhoto>?>(null) }
    fun reload() { scope.launch { rows = vm.loadArchived() } }
    LaunchedEffect(Unit) { reload() }

    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.gallery_archived), onBack = onBack) }) { pad ->
        val list = rows
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text(stringResource(R.string.gallery_archive_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.fillMaxSize().padding(pad).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(list, key = { it.id }) { p ->
                    var bmp by remember(p.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                    LaunchedEffect(p.id) { bmp = vm.thumbnail(p) }
                    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        bmp?.let { Image(it, contentDescription = p.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        IconButton(
                            onClick = { vm.archive(p.id, false) { reload() } },
                            modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x88000000)),
                        ) { Icon(Icons.Outlined.Unarchive, contentDescription = stringResource(R.string.gallery_unarchive), tint = Color.White) }
                    }
                }
            }
        }
    }
}

/** Minimal inline player for a locally cached clip (no Media3 dependency): a VideoView with controls. */
@Composable
private fun VideoPlayer(file: File, modifier: Modifier = Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.VideoView(ctx).apply {
                val controller = android.widget.MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoPath(file.absolutePath)
                setOnPreparedListener { it.isLooping = false; start() }
            }
        },
    )
}

private var osmConfigured = false
private fun ensureOsm(ctx: android.content.Context) {
    if (osmConfigured) return
    org.osmdroid.config.Configuration.getInstance().apply {
        userAgentValue = ctx.packageName // OSM tile policy requires a UA
        osmdroidBasePath = java.io.File(ctx.filesDir, "osmdroid")
        osmdroidTileCache = java.io.File(ctx.cacheDir, "osmdroid-tiles")
    }
    osmConfigured = true
}

/** A small embedded OpenStreetMap centred on the photo's GPS, with a marker; tapping opens a maps app. */
@Composable
private fun GalleryMiniMap(lat: Double, lng: Double, onOpen: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    ensureOsm(ctx)
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 160.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable { onOpen() },
        factory = { c ->
            org.osmdroid.views.MapView(c).apply {
                setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                setMultiTouchControls(false)
                setOnTouchListener { _, _ -> onOpen(); true } // tap-through to a full maps app
                controller.setZoom(15.0)
                val p = org.osmdroid.util.GeoPoint(lat, lng)
                controller.setCenter(p)
                overlays.add(org.osmdroid.views.overlay.Marker(this).apply {
                    position = p
                    setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                })
            }
        },
        onRelease = { it.onDetach() },
    )
}

/** On-device reverse geocode (no network dependency of ours) → a short place label, or null. */
private suspend fun reverseGeocode(ctx: android.content.Context, lat: Double, lng: Double): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val gc = android.location.Geocoder(ctx)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val def = kotlinx.coroutines.CompletableDeferred<String?>()
                gc.getFromLocation(lat, lng, 1) { addrs -> def.complete(addrs.firstOrNull()?.let(::addrLabel)) }
                kotlinx.coroutines.withTimeoutOrNull(4000) { def.await() }
            } else {
                @Suppress("DEPRECATION") gc.getFromLocation(lat, lng, 1)?.firstOrNull()?.let(::addrLabel)
            }
        }.getOrNull()
    }

private fun addrLabel(a: android.location.Address): String =
    listOfNotNull(a.locality ?: a.subAdminArea, a.countryName).joinToString(", ").ifBlank { a.getAddressLine(0) ?: "" }

/** Open the coordinates in a maps app (geo: URI with an optional label). */
private fun openInMaps(ctx: android.content.Context, lat: Double, lng: Double, label: String?) {
    val q = if (label != null) "$lat,$lng(${android.net.Uri.encode(label)})" else "$lat,$lng"
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lng?q=$q"))
    runCatching { ctx.startActivity(intent) }
}

/** Human-readable byte size (1 KB = 1024). */
internal fun formatBytesG(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(java.util.Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}
