package de.ledgerline.app.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

/** A small non-interactive-ish OSM map centered on [lat]/[lng] with a pin.
 *  Tapping anywhere on it invokes [onTap] (used to open a navigation app).
 *
 *  MapLibre port of the old osmdroid map: same OSM raster tiles, but driven through the
 *  GL MapView. The whole map is treated as one tap target — every map click (marker or
 *  empty) calls [onTap], preserving the osmdroid behavior where any touch opened maps. */
@Composable
fun OsmMap(lat: Double, lng: Double, modifier: Modifier = Modifier, onTap: () -> Unit) {
    // Don't fetch tiles for the photo's private coordinates unless the user opted in (M3).
    MapTilesGate(modifier) { OsmMapContent(lat, lng, modifier, onTap) }
}

@Composable
private fun OsmMapContent(lat: Double, lng: Double, modifier: Modifier, onTap: () -> Unit) {
    val context = LocalContext.current
    val onTapRef = remember { onTap }

    val mapView = remember {
        MapView(context).apply {
            // GL surface must be created before it can be started/resumed.
            onCreate(null)
            getMapAsync { map ->
                // Disable interaction — this is a static preview; tapping just opens maps.
                map.uiSettings.apply {
                    setAllGesturesEnabled(false)
                    isAttributionEnabled = true
                    isLogoEnabled = false
                    isCompassEnabled = false
                }
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(lat, lng))
                    .zoom(14.0)
                    .build()
                map.setStyle(Style.Builder().fromJson(OSM_RASTER_STYLE_JSON)) { style ->
                    style.addMarkerIcon(context)
                    val symbolManager = SymbolManager(this, map, style).apply {
                        iconAllowOverlap = true
                        iconIgnorePlacement = true
                    }
                    symbolManager.create(
                        SymbolOptions()
                            .withLatLng(LatLng(lat, lng))
                            .withIconImage(MARKER_ICON_ID)
                            .withIconAnchor("bottom"),
                    )
                }
                // Any tap on the map (interaction is otherwise disabled) opens maps.
                map.addOnMapClickListener { onTapRef(); true }
            }
        }
    }

    // Drive the GL lifecycle: start+resume now, stop+destroy on dispose. Without this the
    // GL surface is never resumed and the map renders blank.
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
        modifier = modifier,
        factory = { mapView },
    )
}
