package de.ledgerline.app.ui.gallery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import de.ledgerline.app.ui.workspace.common.CenteredMessage
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.humanSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(modifier: Modifier = Modifier, vm: GalleryViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val uploadProgress by vm.uploadProgress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var openId by remember { mutableStateOf<String?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val sources = uris.map { uri ->
                PhotoSource(
                    name = queryPhotoName(context, uri),
                    mime = context.contentResolver.getType(uri) ?: "image/jpeg",
                    read = { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } },
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
                snackbarHostState.showSnackbar(
                    context.resources.getString(R.string.gallery_upload_failed, count)
                )
            }
            vm.clearMessage()
        }
    }

    // Camera capture screen — full-screen, like the photo viewer.
    if (showCamera) {
        CameraCaptureScreen(
            onCaptured = { bytes, lat, lng ->
                showCamera = false
                val ts = System.currentTimeMillis()
                vm.uploadAll(
                    listOf(
                        PhotoSource(
                            name = "IMG_$ts.jpg",
                            mime = "image/jpeg",
                            read = { bytes },
                            lat = lat,
                            lng = lng,
                        )
                    )
                )
            },
            onBack = { showCamera = false },
        )
        return
    }

    val current = openId
    if (current != null) {
        val photo = vm.photoById(current)
        if (photo != null) {
            PhotoViewerScreen(photo, vm, onBack = { openId = null }, modifier = modifier)
            return
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
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
                    items(ui.photos, key = { it.id }) { photo ->
                        ThumbCell(photo, vm) { openId = photo.id }
                    }
                }
            }
        }

        // FAB with chooser menu (upload from picker or take a photo).
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            FloatingActionButton(onClick = { fabExpanded = true }) {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = stringResource(R.string.gallery_add),
                )
            }
            DropdownMenu(
                expanded = fabExpanded,
                onDismissRequest = { fabExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gallery_upload_photos)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                    },
                    onClick = {
                        fabExpanded = false
                        vm.armLockSuppression()
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gallery_take_photo)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                    },
                    onClick = {
                        fabExpanded = false
                        showCamera = true
                    },
                )
            }
        }

        // Upload progress overlay.
        val p = uploadProgress
        if (p != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = stringResource(R.string.gallery_uploading, p.current, p.total),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Snackbar host at bottom.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Resolve DISPLAY_NAME from a content URI; falls back to "photo.jpg". */
private fun queryPhotoName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (ni >= 0 && c.moveToFirst() && !c.isNull(ni)) return c.getString(ni)
    }
    return "photo.jpg"
}

@Composable
internal fun ThumbCell(photo: GalleryPhoto, vm: GalleryViewModel, onClick: () -> Unit) {
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, photo.id) {
        value = vm.thumb(photo)
    }
    Box(
        Modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
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
        }
    }
}
