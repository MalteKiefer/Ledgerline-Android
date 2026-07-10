package de.ledgerline.app.ui.gallery

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.model.PhotoPlace
import de.ledgerline.app.ui.workspace.LocalFullscreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    photo: GalleryPhoto,
    vm: GalleryViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }

    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    var showInfo by remember { mutableStateOf(false) }

    // Load medium rendition, fall back to thumb bytes if mediumRef is absent.
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, photo.id) {
        val ref = photo.mediumRef ?: photo.thumbRef
        val key = photo.mediumKey ?: photo.thumbKey
        value = if (ref != null && key != null) {
            when (val r = vm.downloadBytes(ref, key)) {
                is Outcome.Ok -> BitmapFactory.decodeByteArray(r.value, 0, r.value.size)
                is Outcome.Err -> null
            }
        } else {
            null
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(photo.created?.take(10) ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showInfo = !showInfo }) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.info_title))
                    }
                },
            )
        },
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            when {
                b != null -> Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = ox,
                            translationY = oy,
                        )
                        .pointerInput(Unit) {
                            detectTransformGestures { _, panChange, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                ox += panChange.x
                                oy += panChange.y
                            }
                        },
                )
                photo.media_type == "video" -> Text(
                    text = stringResource(R.string.photo_video_soon),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        if (showInfo) {
            PhotoInfoSheet(photo = photo, vm = vm, onDismiss = { showInfo = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoInfoSheet(
    photo: GalleryPhoto,
    vm: GalleryViewModel,
    onDismiss: () -> Unit,
) {
    var place by remember { mutableStateOf<PhotoPlace?>(null) }
    LaunchedEffect(photo.id) {
        place = vm.loadPlace(photo)
    }

    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        val unknown = stringResource(R.string.info_unknown)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.info_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            // File name
            InfoRow(
                label = stringResource(R.string.info_file),
                value = photo.name?.takeIf { it.isNotBlank() } ?: unknown,
            )

            // Taken / created date
            val dateRaw = photo.taken_at ?: photo.created
            val dateValue = if (dateRaw != null) {
                if (dateRaw.length >= 10) dateRaw.take(10) else dateRaw
            } else unknown
            InfoRow(label = stringResource(R.string.info_taken), value = dateValue)

            // Camera
            InfoRow(
                label = stringResource(R.string.info_camera),
                value = photo.camera?.takeIf { it.isNotBlank() } ?: unknown,
            )

            // Resolution
            val resValue = if (photo.width != null && photo.height != null) {
                val mp = photo.width * photo.height / 1_000_000.0
                "${photo.width} × ${photo.height}  (${String.format("%.1f MP", mp)})"
            } else unknown
            InfoRow(label = stringResource(R.string.info_resolution), value = resValue)

            // Location — show coords immediately, replace with readable place when loaded
            val locationValue = when {
                place != null -> {
                    val display = place!!.display?.takeIf { it.isNotBlank() }
                    if (display != null) {
                        display
                    } else {
                        listOfNotNull(place!!.city, place!!.state, place!!.country)
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                            .ifBlank { unknown }
                    }
                }
                photo.lat != null && photo.lng != null ->
                    "%.5f, %.5f".format(photo.lat, photo.lng)
                else -> unknown
            }
            val lat = photo.lat
            val lng = photo.lng
            val mapLabel = place?.display?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(place?.city, place?.country).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Photo" }
            if (lat != null && lng != null) {
                InfoRow(
                    label = stringResource(R.string.info_location),
                    value = locationValue,
                    onClick = { openInMaps(context, lat, lng, mapLabel) },
                )
                // Wrap the native MapView in a clipped Box so it can't overdraw the
                // info rows above it while the sheet scrolls.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .height(160.dp)
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    OsmMap(
                        lat = lat,
                        lng = lng,
                        modifier = Modifier.fillMaxSize(),
                        onTap = { openInMaps(context, lat, lng, mapLabel) },
                    )
                }
                Text(
                    text = stringResource(R.string.map_open_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            } else {
                InfoRow(label = stringResource(R.string.info_location), value = locationValue)
            }
        }
    }
}

private fun openInMaps(context: Context, lat: Double, lng: Double, label: String) {
    val enc = Uri.encode(label)
    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($enc)")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    Row(modifier = rowModifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.6f),
        )
    }
}
