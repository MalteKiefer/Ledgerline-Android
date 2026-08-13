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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
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
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(photo.id, photo.version) {
        bmp = vm.preview(photo)
        loadFailed = bmp == null
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
            IconButton(onClick = { vm.rotate(photo.id, photo.rotation) }) {
                Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = stringResource(R.string.gallery_rotate))
            }
            IconButton(onClick = { showExif = true; scope.launch { exif = vm.exif(photo.id) } }) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.files_info))
            }
            IconButton(onClick = { saveLauncher.launch(photo.name) }) {
                Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.files_save_device))
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        })
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(Color.Black), contentAlignment = Alignment.Center) {
            val b = bmp
            when {
                b != null -> Image(b, contentDescription = photo.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                loadFailed -> Text(
                    if (photo.isVideo) stringResource(R.string.gallery_video_open_hint) else stringResource(R.string.gallery_preview_pending),
                    color = Color.White, modifier = Modifier.padding(24.dp),
                )
                else -> CircularProgressIndicator(color = Color.White)
            }
            msg?.let { Text(it, Modifier.align(Alignment.BottomCenter).padding(12.dp), color = MaterialTheme.colorScheme.primary) }
        }
    }

    if (showExif) ExifSheet(photo, exif, onDismiss = { showExif = false })
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.action_delete)) },
        text = { Text(stringResource(R.string.gallery_delete_confirm)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(photo.id) { onClose() } }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ExifSheet(photo: GalleryPhoto, exif: GalleryExif?, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(photo.name, style = MaterialTheme.typography.titleMedium)
            InfoLine(stringResource(R.string.files_size), formatBytesG(photo.size))
            photo.width?.let { w -> photo.height?.let { h -> InfoLine(stringResource(R.string.gallery_dimensions), "$w × $h") } }
            (exif?.takenAt ?: photo.takenAt)?.let { InfoLine(stringResource(R.string.gallery_taken), it.take(19).replace('T', ' ')) }
            (exif?.camera ?: photo.camera)?.let { InfoLine(stringResource(R.string.gallery_camera), it) }
            (exif?.place ?: photo.place)?.let { InfoLine(stringResource(R.string.gallery_place), it) }
            exif?.exif?.forEach { (section, tags) ->
                Text(section, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                tags.forEach { (k, v) -> InfoLine(k, v) }
            }
        }
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

/** Human-readable byte size (1 KB = 1024). */
internal fun formatBytesG(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(java.util.Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}
