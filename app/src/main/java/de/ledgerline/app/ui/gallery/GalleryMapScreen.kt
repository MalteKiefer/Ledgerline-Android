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
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

/**
 * Full-gallery map: every non-trashed geotagged photo gets a marker on one MapLibre
 * map (mirrors the web `renderMap`). On style-load the markers are added in a single
 * pass through one [SymbolManager] and the camera fits the bounds of all of them (with
 * padding); a single photo centers on it. Tapping a marker opens that photo via
 * [onOpenPhoto] (MapLibre port of the old osmdroid FolderOverlay + Marker setup).
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
                // Gate tile fetches for the user's private photo coordinates behind opt-in (M3):
                // when disabled the MapView below is never built, so no request is sent.
                MapTilesGate(Modifier.fillMaxSize()) {
                    val mapView = remember { buildMap(context, photos, onOpenPhoto) }
                    // Drive the GL lifecycle so the map renders (no lifecycle → blank map).
                    DisposableEffect(Unit) {
                        mapView.onStart()
                        mapView.onResume()
                        onDispose {
                            mapView.onPause()
                            mapView.onStop()
                            mapView.onDestroy()
                        }
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { mapView },
                    )
                }
            }
        }
    }
}

/** Build the MapLibre [MapView] with one marker per photo on a single SymbolManager and
 *  fit the camera to all markers. [photos] is guaranteed non-empty and geotagged. */
private fun buildMap(
    context: android.content.Context,
    photos: List<GalleryPhoto>,
    onOpenPhoto: (String) -> Unit,
): MapView = MapView(context).apply {
    onCreate(null)
    getMapAsync { map ->
        map.uiSettings.apply {
            isLogoEnabled = false
            isAttributionEnabled = true
        }
        map.setStyle(Style.Builder().fromJson(OSM_RASTER_STYLE_JSON)) { style ->
            style.addMarkerIcon(context)

            // Add all markers in one pass through a single SymbolManager (cheap for 300+).
            // Map each symbol id back to a photo id so a marker tap opens that photo.
            val symbolManager = SymbolManager(this, map, style).apply {
                iconAllowOverlap = true
                iconIgnorePlacement = true
            }
            val photoIdBySymbol = HashMap<Long, String>(photos.size)
            for (photo in photos) {
                val symbol = symbolManager.create(
                    SymbolOptions()
                        .withLatLng(LatLng(photo.lat!!, photo.lng!!))
                        .withIconImage(MARKER_ICON_ID)
                        .withIconAnchor("bottom"),
                )
                photoIdBySymbol[symbol.id] = photo.id
            }
            symbolManager.addClickListener { symbol ->
                photoIdBySymbol[symbol.id]?.let(onOpenPhoto) != null
            }
        }

        // Camera fit: single photo → center+zoom; multiple → fit bounds with padding.
        // Deferred inside getMapAsync so the map is laid out before the camera moves.
        val distinct = photos.map { LatLng(it.lat!!, it.lng!!) }
            .distinctBy { it.latitude to it.longitude }
        if (distinct.size == 1) {
            // Single point (or all photos share one coordinate) → center + fixed zoom.
            // LatLngBounds can't be built from a degenerate box, so guard it here.
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(distinct[0], 14.0))
        } else {
            val boundsBuilder = LatLngBounds.Builder()
            for (p in distinct) boundsBuilder.include(p)
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 96))
        }
    }
}
