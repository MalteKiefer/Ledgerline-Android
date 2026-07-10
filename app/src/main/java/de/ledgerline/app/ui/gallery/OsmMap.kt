package de.ledgerline.app.ui.gallery

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** A small non-interactive-ish OSM map centered on [lat]/[lng] with a pin.
 *  Tapping anywhere on it invokes [onTap] (used to open a navigation app). */
@Composable
fun OsmMap(lat: Double, lng: Double, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val context = LocalContext.current
    val onTapRef = remember { onTap }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            isTilesScaledToDpi = true
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(lat, lng))
            val marker = Marker(this).apply {
                position = GeoPoint(lat, lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            overlays.add(marker)
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_UP) {
                    v.performClick()
                    onTapRef()
                }
                false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { it.controller.setCenter(GeoPoint(lat, lng)) },
    )
}
