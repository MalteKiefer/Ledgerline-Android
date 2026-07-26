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
import androidx.compose.runtime.getValue
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

/**
 * Full-gallery map: every non-trashed geotagged photo becomes a point in one clustered
 * MapLibre GeoJSON source (mirrors the web `renderMap` + MarkerCluster). Nearby photos
 * group into a numbered bubble at low zoom and split apart as you zoom in; the camera
 * fits the bounds of all of them (a single photo centers on it). Tapping a cluster zooms
 * in, tapping a single photo opens it via [onOpenPhoto].
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
    // Geotagged set, with a lazy backfill of coordinates from older photos' meta blobs.
    // Starts with the record-geotagged set immediately, then expands once the backfill runs.
    val photos by androidx.compose.runtime.produceState(initialValue = vm.geotaggedPhotos()) {
        value = vm.geotaggedWithBackfill()
    }

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
                    // Rebuild once if the backfill expands the geotagged set (key on the list).
                    androidx.compose.runtime.key(photos) {
                        val mapView = remember { buildMap(context, photos, onOpenPhoto) }
                        // Drive the GL lifecycle so the map renders (no lifecycle → blank map).
                        DisposableEffect(mapView) {
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
}

private const val PHOTOS_SOURCE = "photos-src"
private const val CLUSTER_LAYER = "photo-clusters"
private const val CLUSTER_COUNT_LAYER = "photo-cluster-count"
private const val POINT_LAYER = "photo-points"

/** Build the MapLibre [MapView] with a CLUSTERED GeoJSON source (one point per photo) and
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

            // One clustered GeoJSON source: MapLibre groups nearby points into clusters at
            // low zoom (fast for thousands of photos) and splits them as you zoom in.
            val features = photos.map { p ->
                org.maplibre.geojson.Feature.fromGeometry(
                    org.maplibre.geojson.Point.fromLngLat(p.lng!!, p.lat!!),
                ).apply { addStringProperty("id", p.id) }
            }
            style.addSource(
                org.maplibre.android.style.sources.GeoJsonSource(
                    PHOTOS_SOURCE,
                    org.maplibre.geojson.FeatureCollection.fromFeatures(features),
                    org.maplibre.android.style.sources.GeoJsonOptions()
                        .withCluster(true)
                        .withClusterRadius(50)
                        .withClusterMaxZoom(16),
                ),
            )
            // Cluster bubbles (only features that carry point_count), sized by count.
            style.addLayer(
                org.maplibre.android.style.layers.CircleLayer(CLUSTER_LAYER, PHOTOS_SOURCE)
                    .withProperties(
                        org.maplibre.android.style.layers.PropertyFactory.circleColor(android.graphics.Color.parseColor("#3B82F6")),
                        org.maplibre.android.style.layers.PropertyFactory.circleOpacity(0.85f),
                        org.maplibre.android.style.layers.PropertyFactory.circleRadius(
                            org.maplibre.android.style.expressions.Expression.step(
                                org.maplibre.android.style.expressions.Expression.get("point_count"),
                                org.maplibre.android.style.expressions.Expression.literal(16),
                                org.maplibre.android.style.expressions.Expression.stop(10, 22),
                                org.maplibre.android.style.expressions.Expression.stop(50, 28),
                                org.maplibre.android.style.expressions.Expression.stop(200, 34),
                            ),
                        ),
                    )
                    .withFilter(org.maplibre.android.style.expressions.Expression.has("point_count")),
            )
            // Cluster count label.
            style.addLayer(
                org.maplibre.android.style.layers.SymbolLayer(CLUSTER_COUNT_LAYER, PHOTOS_SOURCE)
                    .withProperties(
                        org.maplibre.android.style.layers.PropertyFactory.textField(
                            org.maplibre.android.style.expressions.Expression.toString(
                                org.maplibre.android.style.expressions.Expression.get("point_count"),
                            ),
                        ),
                        org.maplibre.android.style.layers.PropertyFactory.textSize(12f),
                        org.maplibre.android.style.layers.PropertyFactory.textColor(android.graphics.Color.WHITE),
                        org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap(true),
                        org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement(true),
                    )
                    .withFilter(org.maplibre.android.style.expressions.Expression.has("point_count")),
            )
            // Unclustered single photos → the pin icon.
            style.addLayer(
                org.maplibre.android.style.layers.SymbolLayer(POINT_LAYER, PHOTOS_SOURCE)
                    .withProperties(
                        org.maplibre.android.style.layers.PropertyFactory.iconImage(MARKER_ICON_ID),
                        org.maplibre.android.style.layers.PropertyFactory.iconAnchor("bottom"),
                        org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                    )
                    .withFilter(
                        org.maplibre.android.style.expressions.Expression.not(
                            org.maplibre.android.style.expressions.Expression.has("point_count"),
                        ),
                    ),
            )

            // Tap: a cluster zooms in; a single photo opens.
            map.addOnMapClickListener { latLng ->
                val screen = map.projection.toScreenLocation(latLng)
                val rect = android.graphics.RectF(screen.x - 24, screen.y - 24, screen.x + 24, screen.y + 24)
                val hit = map.queryRenderedFeatures(rect, CLUSTER_LAYER, POINT_LAYER).firstOrNull()
                when {
                    hit == null -> false
                    hit.hasProperty("point_count") -> {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, map.cameraPosition.zoom + 2.0))
                        true
                    }
                    else -> {
                        hit.getStringProperty("id")?.let(onOpenPhoto)
                        true
                    }
                }
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
