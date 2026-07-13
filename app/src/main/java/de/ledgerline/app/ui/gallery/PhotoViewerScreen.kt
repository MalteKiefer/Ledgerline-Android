package de.ledgerline.app.ui.gallery

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import de.ledgerline.app.R
import de.ledgerline.app.core.ErrorKind
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
    var showDatePicker by remember { mutableStateOf(false) }
    var showLocationPicker by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var playingMotion by remember { mutableStateOf(false) }

    val isVideo = photo.media_type == "video"
    // A live/motion photo is a still IMAGE that carries an embedded motion clip.
    val hasMotion = !isVideo && photo.motionRef != null && photo.motionKey != null
    // Stop any motion playback when navigating to a different photo.
    LaunchedEffect(photo.id) { playingMotion = false }

    // Location picker is full-screen — replaces the viewer while open.
    if (showLocationPicker) {
        LocationPickerScreen(
            initialLat = photo.lat,
            initialLng = photo.lng,
            onPick = { lat, lng ->
                vm.setLocation(setOf(photo.id), lat, lng)
                showLocationPicker = false
            },
            onBack = { showLocationPicker = false },
            modifier = modifier,
        )
        return
    }

    // Image path: load medium rendition, fall back to thumb bytes if mediumRef is
    // absent. Skipped entirely for videos (handled by VideoPlayer below).
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, photo.id) {
        if (isVideo) return@produceState
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
                title = { Text(formatTakenAt(photo.taken_at ?: photo.created)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                // Actions moved to the floating bottom bar (see below).
            )
        },
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .onSizeChanged { viewport = it },
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            when {
                isVideo -> VideoPlayer(photo = photo, vm = vm, modifier = Modifier.fillMaxSize())
                b != null -> {
                    // For a 90/270 rotation the image's bounding box swaps W/H, so the
                    // rotated bitmap (drawn at ContentScale.Fit = 1×) would overflow. Snap
                    // it to a fit-scale so the rotated content fits inside the viewport
                    // (mirrors the web's _fitViewer). For 0/180 the Image already fits.
                    val quarter = photo.rotation % 180 != 0
                    val fitScale = if (quarter && viewport.width > 0 && viewport.height > 0) {
                        // The bitmap is laid out to fit the viewport at 0° first; after a
                        // quarter turn its on-screen extents swap, so fit the swapped box.
                        val fitted = fitInside(b.width.toFloat(), b.height.toFloat(), viewport.width.toFloat(), viewport.height.toFloat())
                        minOf(viewport.width / fitted.second, viewport.height / fitted.first)
                    } else {
                        1f
                    }
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                // Combine user zoom (scale) with the rotation fit-scale, plus
                                // the flip sign — never clobber the pan/zoom state.
                                scaleX = scale * fitScale * (if (photo.flipH) -1f else 1f),
                                scaleY = scale * fitScale * (if (photo.flipV) -1f else 1f),
                                rotationZ = photo.rotation.toFloat(),
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
                }
                else -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            // Live/motion photo: overlay the looping clip on top of the still, or
            // show a tappable motion affordance when idle. Only for image photos
            // that carry an embedded motion clip.
            if (hasMotion) {
                if (playingMotion) {
                    MotionPlayer(
                        photo = photo,
                        vm = vm,
                        onStop = { playingMotion = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (b != null) {
                    IconButton(
                        onClick = { playingMotion = true },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    ) {
                        Icon(
                            Icons.Outlined.MotionPhotosOn,
                            contentDescription = stringResource(R.string.action_play_motion),
                            tint = Color.White,
                        )
                    }
                }
            }

            // Floating action bar — the photo actions live here (moved off the top bar):
            // rotate / flip (images only), favorite, info. Dark scrim + white icons to stay
            // legible over any photo; sits above the navigation-bar inset.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isVideo) {
                    IconButton(onClick = { vm.rotatePhoto(photo.id) }) {
                        Icon(Icons.AutoMirrored.Outlined.RotateRight, stringResource(R.string.action_rotate), tint = Color.White)
                    }
                    IconButton(onClick = { vm.flipHorizontal(photo.id) }) {
                        Icon(Icons.Outlined.Flip, stringResource(R.string.action_flip_h), tint = Color.White)
                    }
                    IconButton(onClick = { vm.flipVertical(photo.id) }) {
                        Icon(
                            Icons.Outlined.Flip,
                            stringResource(R.string.action_flip_v),
                            modifier = Modifier.graphicsLayer(rotationZ = 90f),
                            tint = Color.White,
                        )
                    }
                }
                IconButton(onClick = { vm.toggleFavorite(photo.id) }) {
                    Icon(
                        if (photo.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        stringResource(if (photo.favorite) R.string.action_unfavorite else R.string.action_favorite),
                        tint = if (photo.favorite) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = { showInfo = !showInfo }) {
                    Icon(Icons.Outlined.Info, stringResource(R.string.info_title), tint = Color.White)
                }
            }
        }

        if (showInfo) {
            PhotoInfoSheet(
                photo = photo,
                vm = vm,
                onDismiss = { showInfo = false },
                onEditDate = { showInfo = false; showDatePicker = true },
                onEditLocation = { showInfo = false; showLocationPicker = true },
            )
        }
    }

    // Single-photo date edit — day-granularity picker, applies to this photo only.
    if (showDatePicker) {
        PhotoDatePickerDialog(
            initialIso = photo.taken_at ?: photo.created,
            onConfirm = { iso -> vm.setDate(setOf(photo.id), iso) },
            onDismiss = { showDatePicker = false },
        )
    }
}

/**
 * In-memory encrypted-video playback.
 *
 * We decrypt the whole blob to a [ByteArray] in RAM (via [GalleryViewModel.downloadBytes],
 * which runs the secretstream decrypt) and feed those bytes straight into ExoPlayer through a
 * [ByteArrayDataSource]. Plaintext video bytes therefore never touch disk — they live only in
 * memory while this composable is on screen.
 *
 * Ref selection: use `originalRef/originalKey` — the actual uploaded video. (`medium` is a poster
 * IMAGE for videos, which ExoPlayer can't play → UnrecognizedInputFormatException.)
 *
 * MVP note: loading the full original video into RAM is acceptable for now. A future optimization
 * for very large originals is a streaming secretstream-backed [DataSource] that decrypts chunk by
 * chunk on demand instead of materializing the whole file.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(
    photo: GalleryPhoto,
    vm: GalleryViewModel,
    modifier: Modifier = Modifier,
) {
    // For videos the ORIGINAL is the playable file; `medium` is a poster image
    // (an image container ExoPlayer can't play). Use the original video bytes.
    InMemoryPlayer(
        vm = vm,
        key = photo.id,
        ref = photo.originalRef ?: photo.mediumRef,
        encKey = photo.originalKey ?: photo.mediumKey,
        showControls = true,
        loop = false,
        modifier = modifier,
    )
}

/**
 * Live/motion-photo playback: the still's embedded motion clip, looped, no controls,
 * tap anywhere to return to the still. Reuses the same in-memory decrypt+ExoPlayer path
 * as [VideoPlayer]. If the clip fails to decrypt/play, we return to the still (no crash).
 */
@Composable
private fun MotionPlayer(
    photo: GalleryPhoto,
    vm: GalleryViewModel,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InMemoryPlayer(
        vm = vm,
        key = "motion:${photo.id}",
        ref = photo.motionRef,
        encKey = photo.motionKey,
        showControls = false,
        loop = true,
        onError = onStop,
        modifier = modifier.clickable(onClick = onStop),
    )
}

/**
 * In-memory encrypted-video playback shared by full-video and live-motion playback.
 *
 * We decrypt the whole blob to a [ByteArray] in RAM (via [GalleryViewModel.downloadBytes],
 * which runs the secretstream decrypt) and feed those bytes straight into ExoPlayer through a
 * [ByteArrayDataSource]. Plaintext bytes therefore never touch disk — they live only in memory
 * while this composable is on screen. The ExoPlayer is released on dispose so it never leaks.
 *
 * MVP note: loading the full original into RAM is acceptable for now. A future optimization for
 * very large originals is a streaming secretstream-backed [DataSource] that decrypts chunk by
 * chunk on demand instead of materializing the whole file.
 */
@OptIn(UnstableApi::class)
@Composable
private fun InMemoryPlayer(
    vm: GalleryViewModel,
    key: Any,
    ref: String?,
    encKey: String?,
    showControls: Boolean,
    loop: Boolean,
    modifier: Modifier = Modifier,
    onError: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    // Decrypt the bytes off the UI, mirroring the photo path's produceState.
    val bytes by produceState<Outcome<ByteArray>?>(initialValue = null, key) {
        value = if (ref != null && encKey != null) {
            vm.downloadBytes(ref, encKey)
        } else {
            Outcome.Err(ErrorKind.NOT_CONFIGURED)
        }
    }

    // One ExoPlayer per open player; released on dispose so it never leaks.
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // When the decrypted bytes arrive, wire them into the player from memory.
    val ok = bytes as? Outcome.Ok
    LaunchedEffect(ok) {
        val data = ok?.value ?: return@LaunchedEffect
        player.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        val factory = DataSource.Factory { ByteArrayDataSource(data) }
        val source = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    // A decrypt/setup failure bubbles up so callers (e.g. motion) can drop back to the still.
    val err = bytes as? Outcome.Err
    LaunchedEffect(err) {
        if (err != null) onError?.invoke()
    }

    when (bytes) {
        null -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        is Outcome.Err -> if (onError == null) {
            Text(
                text = stringResource(R.string.video_load_failed),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is Outcome.Ok -> AndroidView(
            modifier = modifier,
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = showControls
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoInfoSheet(
    photo: GalleryPhoto,
    vm: GalleryViewModel,
    onDismiss: () -> Unit,
    onEditDate: () -> Unit,
    onEditLocation: () -> Unit,
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
            if (lat != null && lng != null) {
                InfoRow(
                    label = stringResource(R.string.info_location),
                    value = locationValue,
                    onClick = { openInMaps(context, lat, lng) },
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
                        onTap = { openInMaps(context, lat, lng) },
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

            // Edit actions — day-granularity date + map location picker (this photo).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                TextButton(onClick = onEditDate, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                    Text(
                        text = stringResource(R.string.action_edit_date),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                TextButton(onClick = onEditLocation, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Place, contentDescription = null)
                    Text(
                        text = stringResource(R.string.action_edit_location),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * The on-screen size of a `srcW × srcH` image scaled with ContentScale.Fit into a
 * `boxW × boxH` viewport. Returns `(height, width)` of the fitted image.
 */
private fun fitInside(srcW: Float, srcH: Float, boxW: Float, boxH: Float): Pair<Float, Float> {
    if (srcW <= 0f || srcH <= 0f) return boxH to boxW
    val s = minOf(boxW / srcW, boxH / srcH)
    return (srcH * s) to (srcW * s)
}

private fun openInMaps(context: Context, lat: Double, lng: Double) {
    // Coordinates only — no address label appended.
    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
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

/** Format an ISO-8601 timestamp as a local "dd.MM.yyyy HH:mm"; falls back to the raw
 *  string (trimmed) when it can't be parsed, and to "" when null/blank. */
private fun formatTakenAt(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        java.time.OffsetDateTime.parse(iso)
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    }.getOrElse { iso.take(16).replace('T', ' ') }
}
