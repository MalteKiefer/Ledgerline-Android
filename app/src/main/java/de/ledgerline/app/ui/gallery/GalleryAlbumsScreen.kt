package de.ledgerline.app.ui.gallery

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import de.ledgerline.app.domain.model.gallery.GalleryAlbum
import de.ledgerline.app.domain.model.gallery.GalleryPhoto
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import kotlinx.coroutines.launch

/** Album list → create / rename / delete, and open an album into its photo grid. */
@Composable
fun GalleryAlbumsScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var albums by remember { mutableStateOf<List<GalleryAlbum>?>(null) }
    var opened by remember { mutableStateOf<GalleryAlbum?>(null) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<GalleryAlbum?>(null) }
    fun reload() { scope.launch { albums = vm.albums() } }
    LaunchedEffect(Unit) { reload() }

    opened?.let { album ->
        GalleryAlbumGridScreen(vm, album, onBack = { opened = null; reload() })
        return
    }

    AppScaffold(topBar = {
        AppTopBar(title = stringResource(R.string.gallery_albums), onBack = onBack, actions = {
            IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.gallery_album_new)) }
        })
    }) { pad ->
        val list = albums
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text(stringResource(R.string.gallery_albums_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyColumn(Modifier.fillMaxSize().padding(pad).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.id }) { a ->
                    var cover by remember(a.id, a.coverPhotoId) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                    LaunchedEffect(a.id, a.coverPhotoId) { cover = vm.thumbById(a.coverPhotoId) }
                    Row(
                        Modifier.fillMaxWidth().clickable { opened = a }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            if (cover != null) Image(cover!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(a.name, style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.gallery_album_count, a.count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; renaming = a })
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; vm.deleteAlbum(a.id) { reload() } })
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) NameDialog(title = stringResource(R.string.gallery_album_new), initial = "", onConfirm = { name -> creating = false; vm.createAlbum(name) { reload() } }, onDismiss = { creating = false })
    renaming?.let { a -> NameDialog(title = stringResource(R.string.action_rename), initial = a.name, onConfirm = { name -> renaming = null; vm.renameAlbum(a.id, name) { reload() } }, onDismiss = { renaming = null }) }
}

/** One album's photos with its own lightbox; selection removes photos from the album. */
@Composable
fun GalleryAlbumGridScreen(vm: GalleryViewModel, album: GalleryAlbum, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var photos by remember(album.id) { mutableStateOf<List<GalleryPhoto>?>(null) }
    var lightbox by remember { mutableStateOf<GalleryPhoto?>(null) }
    val selection = remember { androidx.compose.runtime.mutableStateListOf<Int>() }
    fun reload() { scope.launch { photos = vm.albumPhotos(album.id) } }
    LaunchedEffect(album.id) { reload() }

    lightbox?.let { p -> GalleryLightbox(vm, p, onClose = { lightbox = null; reload() }); return }

    AppScaffold(topBar = {
        AppTopBar(title = album.name, onBack = onBack, actions = {
            if (selection.isNotEmpty()) IconButton(onClick = {
                vm.removeFromAlbum(album.id, selection.toList()) { ok -> if (ok) { selection.clear(); reload() } }
            }) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.gallery_album_remove)) }
        })
    }) { pad ->
        val list = photos
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text(stringResource(R.string.gallery_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.fillMaxSize().padding(pad).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(list, key = { it.id }) { p ->
                    var bmp by remember(p.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                    LaunchedEffect(p.id) { bmp = vm.thumbnail(p) }
                    val sel = p.id in selection
                    Box(
                        Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable {
                            if (selection.isNotEmpty()) { if (sel) selection.remove(p.id) else selection.add(p.id) } else lightbox = p
                        },
                    ) {
                        bmp?.let { Image(it, contentDescription = p.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        IconButton(onClick = { if (sel) selection.remove(p.id) else selection.add(p.id) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                            Text(if (sel) "☑" else "☐", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.gallery_album_name)) }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
