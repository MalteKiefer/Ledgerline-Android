package de.ledgerline.app.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.GalleryAlbum
import de.ledgerline.app.ui.workspace.common.CenteredMessage

@Composable
fun AlbumsScreen(
    modifier: Modifier = Modifier,
    galleryVm: GalleryViewModel,
    albumsVm: AlbumsViewModel = hiltViewModel(),
    onOpenAlbum: (String) -> Unit,
) {
    val albums by albumsVm.albums.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<GalleryAlbum?>(null) }
    var deleteTarget by remember { mutableStateOf<GalleryAlbum?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        if (albums.isEmpty()) {
            CenteredMessage(stringResource(R.string.albums_empty))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 116.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(albums, key = { it.id }) { album ->
                    AlbumCard(
                        album = album,
                        albumsVm = albumsVm,
                        galleryVm = galleryVm,
                        onClick = { onOpenAlbum(album.id) },
                        onRename = { renameTarget = album },
                        onDelete = { deleteTarget = album },
                    )
                }
            }
        }
    }

    renameTarget?.let { album ->
        TextInputDialog(
            title = stringResource(R.string.album_rename),
            confirmLabel = stringResource(R.string.album_rename),
            initial = album.name,
            onConfirm = { name ->
                albumsVm.rename(album.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { album ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.album_delete)) },
            text = { Text(album.name) },
            confirmButton = {
                TextButton(onClick = {
                    albumsVm.delete(album.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.album_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AlbumCard(
    album: GalleryAlbum,
    albumsVm: AlbumsViewModel,
    galleryVm: GalleryViewModel,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val cover = remember(album) { albumsVm.coverPhoto(album) }
    val count = remember(album) { albumsVm.count(album) }
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, album.id, cover?.id) {
        value = cover?.let { galleryVm.thumb(it) }
    }

    Column(modifier = Modifier.padding(1.dp).clickable { onClick() }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    b.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else if (cover != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.album_rename)) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.album_delete)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    modifier: Modifier = Modifier,
    galleryVm: GalleryViewModel,
    albumsVm: AlbumsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val albums by albumsVm.albums.collectAsStateWithLifecycle()
    val album = remember(albums, albumId) { albumsVm.albumById(albumId) }
    var openId by remember { mutableStateOf<String?>(null) }

    // Album deleted underneath us — leave the detail view.
    if (album == null) {
        onBack()
        return
    }

    val photos = remember(album) { albumsVm.albumPhotos(album) }

    val current = openId
    if (current != null) {
        val photo = photos.firstOrNull { it.id == current } ?: galleryVm.photoById(current)
        if (photo != null) {
            PhotoViewerScreen(photo, galleryVm, onBack = { openId = null }, modifier = modifier)
            return
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(album.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (photos.isEmpty()) {
                CenteredMessage(stringResource(R.string.albums_empty))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(photos, key = { it.id }) { photo ->
                        Box {
                            ThumbCell(photo, galleryVm) { openId = photo.id }
                            PhotoOverflow(
                                onRemove = { albumsVm.removePhoto(album.id, photo.id) },
                                onSetCover = { albumsVm.setCover(album.id, photo.id) },
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoOverflow(
    onRemove: () -> Unit,
    onSetCover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.album_remove_photo)) },
                onClick = { open = false; onRemove() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.album_set_cover)) },
                onClick = { open = false; onSetCover() },
            )
        }
    }
}

@Composable
internal fun TextInputDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.album_name_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text) },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
