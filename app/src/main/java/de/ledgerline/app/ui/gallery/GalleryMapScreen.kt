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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import de.ledgerline.app.R
import de.ledgerline.app.ui.map.GatedMapsforgeMap
import de.ledgerline.app.ui.map.PhotoPoint
import de.ledgerline.app.ui.map.rememberMapPin
import de.ledgerline.app.ui.map.rememberMapsforgeController
import de.ledgerline.app.ui.workspace.LocalFullscreen
import de.ledgerline.app.ui.workspace.common.CenteredMessage

/**
 * Full-gallery map: every non-trashed geotagged photo becomes a point in one grid-clustered
 * mapsforge overlay ([de.ledgerline.app.ui.map.PhotoClusterLayer]). Nearby photos group into a
 * numbered bubble at low zoom and split apart as you zoom in; the camera fits all of them.
 * Tapping a bubble zooms in, tapping a single photo opens it via [onOpenPhoto].
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
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }

    val photos by produceState(initialValue = vm.geotaggedPhotos()) { value = vm.geotaggedWithBackfill() }
    val controller = rememberMapsforgeController()
    val pin = rememberMapPin()

    LaunchedEffect(photos, pin) {
        if (photos.isEmpty()) return@LaunchedEffect
        controller.setPhotoClusters(
            photos.mapNotNull { p -> p.lat?.let { la -> p.lng?.let { ln -> PhotoPoint(p.id, la, ln) } } },
            pin,
            0xFF3B82F6.toInt(),
            onOpenPhoto,
        )
        val lats = photos.mapNotNull { it.lat }
        val lngs = photos.mapNotNull { it.lng }
        if (lats.isEmpty()) return@LaunchedEffect
        val distinct = photos.mapNotNull { p -> p.lat?.let { la -> p.lng?.let { ln -> la to ln } } }.distinct()
        if (distinct.size == 1) controller.moveTo(distinct[0].first, distinct[0].second, 14)
        else controller.fitBounds(lats.min(), lngs.min(), lats.max(), lngs.max())
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_map)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (photos.isEmpty()) {
                CenteredMessage(stringResource(R.string.gallery_map_empty))
            } else {
                GatedMapsforgeMap(
                    modifier = Modifier.fillMaxSize(),
                    controller = controller,
                    initialLat = photos.firstOrNull()?.lat,
                    initialLng = photos.firstOrNull()?.lng,
                    initialZoom = 12,
                )
            }
        }
    }
}
