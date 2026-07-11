package de.ledgerline.app.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.ui.workspace.LocalFullscreen
import de.ledgerline.app.ui.workspace.common.CenteredMessage
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Full-gallery map: every non-trashed geotagged photo gets a marker on one osmdroid
 * map (mirrors the web `renderMap`). On create the markers are added in a single pass
 * onto one [FolderOverlay] and the camera fits the bounds of all of them (with padding);
 * a single photo centers on it. Tapping a marker opens that photo via [onOpenPhoto].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryMapScreen(
    vm: GalleryViewModel,
    onOpenPhoto: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    // Full-screen: hide the outer workspace chrome so this screen owns its insets.
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }

    val context = LocalContext.current
    // Snapshot the geotagged set once — the map is built imperatively on create.
    val photos = remember { vm.geotaggedPhotos() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_map)) },
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
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (photos.isEmpty()) {
                CenteredMessage(stringResource(R.string.gallery_map_empty))
            } else {
                val mapView = remember { buildMap(context, photos, onOpenPhoto) }
                DisposableEffect(Unit) { onDispose { mapView.onDetach() } }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { mapView },
                )
            }
        }
    }
}

/** Build the osmdroid [MapView] with one marker per photo on a single overlay and
 *  fit the camera to all markers. [photos] is guaranteed non-empty and geotagged. */
private fun buildMap(
    context: android.content.Context,
    photos: List<GalleryPhoto>,
    onOpenPhoto: (String) -> Unit,
): MapView = MapView(context).apply {
    setTileSource(TileSourceFactory.MAPNIK)
    setMultiTouchControls(true)
    isTilesScaledToDpi = true

    // Add all markers in one pass onto a single folder overlay (cheap for 300+).
    val folder = FolderOverlay()
    for (photo in photos) {
        val point = GeoPoint(photo.lat!!, photo.lng!!)
        val marker = Marker(this).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = photo.name
            setOnMarkerClickListener { _, _ -> onOpenPhoto(photo.id); true }
        }
        folder.add(marker)
    }
    overlays.add(folder)

    if (photos.size == 1) {
        controller.setZoom(15.0)
        controller.setCenter(GeoPoint(photos[0].lat!!, photos[0].lng!!))
    } else {
        var north = -90.0
        var south = 90.0
        var east = -180.0
        var west = 180.0
        for (photo in photos) {
            val lat = photo.lat!!
            val lng = photo.lng!!
            if (lat > north) north = lat
            if (lat < south) south = lat
            if (lng > east) east = lng
            if (lng < west) west = lng
        }
        // zoomToBoundingBox needs a laid-out view; defer until the map has a size.
        val box = BoundingBox(north, east, south, west)
        addOnFirstLayoutListener { _, _, _, _, _ ->
            zoomToBoundingBox(box, false, 64)
        }
    }
}
