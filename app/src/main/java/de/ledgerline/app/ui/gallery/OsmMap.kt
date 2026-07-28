package de.ledgerline.app.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import de.ledgerline.app.ui.map.GatedMapsforgeMap
import de.ledgerline.app.ui.map.rememberMapPin
import de.ledgerline.app.ui.map.rememberMapsforgeController

/**
 * A small OSM map centered on [lat]/[lng] with a pin. Tapping anywhere invokes [onTap]
 * (used to open a navigation app). mapsforge port: offline `.map` regions render when
 * installed, otherwise online OSM tiles behind the shared opt-in gate.
 */
@Composable
fun OsmMap(lat: Double, lng: Double, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val controller = rememberMapsforgeController()
    val pin = rememberMapPin()
    LaunchedEffect(lat, lng, pin) {
        controller.moveTo(lat, lng, 14)
        controller.setMarkers(listOf(lat to lng), pin)
    }
    GatedMapsforgeMap(
        modifier = modifier,
        controller = controller,
        initialLat = lat,
        initialLng = lng,
        initialZoom = 14,
        onMapTap = { _, _ -> onTap() },
    )
}
