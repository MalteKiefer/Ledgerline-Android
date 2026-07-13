package de.ledgerline.app.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.ledgerline.app.R
import de.ledgerline.app.data.remote.Geocoder
import de.ledgerline.app.ui.workspace.LocalFullscreen
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

/** Berlin — a sensible default center when the target photo has no coordinates. */
private const val DEFAULT_LAT = 52.520008
private const val DEFAULT_LNG = 13.404954

/**
 * Full-screen location picker: a tappable MapLibre map that drops/moves a marker, plus a
 * Nominatim address search that recenters the map + moves the marker. The confirm FAB
 * returns the marker's lat/lng. Mirrors the web `openLocPicker`/`geoSearch` (single-photo
 * and bulk share this screen). MapLibre port of the old osmdroid MapEventsOverlay setup:
 * `map.addOnMapClickListener` replaces MapEventsReceiver; a single SymbolManager symbol
 * replaces the osmdroid Marker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerScreen(
    initialLat: Double?,
    initialLng: Double?,
    onPick: (Double, Double) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    geocoder: Geocoder = remember { Geocoder() },
) {
    BackHandler(onBack = onBack)
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    val startLat = initialLat ?: DEFAULT_LAT
    val startLng = initialLng ?: DEFAULT_LNG

    // The currently-selected point (marker position). Confirm returns this.
    var pickedLat by rememberSaveable { mutableStateOf(startLat) }
    var pickedLng by rememberSaveable { mutableStateOf(startLng) }

    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var notFound by rememberSaveable { mutableStateOf(false) }

    // The live map + its single marker, wired once the style loads (see factory below).
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val symbolManagerRef = remember { mutableStateOf<SymbolManager?>(null) }
    val markerRef = remember { mutableStateOf<Symbol?>(null) }

    fun moveMarker(lat: Double, lng: Double, recenter: Boolean) {
        pickedLat = lat
        pickedLng = lng
        val point = LatLng(lat, lng)
        val mgr = symbolManagerRef.value
        markerRef.value?.let { sym ->
            sym.latLng = point
            mgr?.update(sym)
        }
        if (recenter) {
            mapRef.value?.animateCamera(CameraUpdateFactory.newLatLng(point))
        }
    }

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                mapRef.value = map
                map.uiSettings.apply {
                    isLogoEnabled = false
                    isAttributionEnabled = true
                }
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(startLat, startLng))
                    .zoom(14.0)
                    .build()
                map.setStyle(Style.Builder().fromJson(OSM_RASTER_STYLE_JSON)) { style ->
                    style.addMarkerIcon(context)
                    val mgr = SymbolManager(this, map, style).apply {
                        iconAllowOverlap = true
                        iconIgnorePlacement = true
                    }
                    symbolManagerRef.value = mgr
                    markerRef.value = mgr.create(
                        SymbolOptions()
                            .withLatLng(LatLng(startLat, startLng))
                            .withIconImage(MARKER_ICON_ID)
                            .withIconAnchor("bottom"),
                    )
                }
                // Tap the map → move the marker to the tapped geo point.
                map.addOnMapClickListener { point ->
                    moveMarker(point.latitude, point.longitude, recenter = false)
                    true
                }
            }
        }
    }

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

    fun doSearch() {
        val q = query.trim()
        if (q.isBlank()) return
        keyboard?.hide()
        searching = true
        notFound = false
        scope.launch {
            val hit = geocoder.search(q)
            searching = false
            if (hit == null) {
                notFound = true
            } else {
                notFound = false
                moveMarker(hit.first, hit.second, recenter = true)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_set_location)) },
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.loc_confirm)) },
                icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                onClick = { onPick(pickedLat, pickedLng) },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { mapView },
            )

            // Address search field floating at the top.
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    notFound = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.loc_search_hint)) },
                leadingIcon = {
                    if (searching) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(4.dp))
                    } else {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    }
                },
                supportingText = if (notFound) {
                    { Text(stringResource(R.string.loc_not_found), color = MaterialTheme.colorScheme.error) }
                } else null,
                isError = notFound,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }),
            )
        }
    }
}
