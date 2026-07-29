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
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.ui.map.GatedMapsforgeMap
import de.ledgerline.app.ui.map.rememberMapPin
import de.ledgerline.app.ui.map.rememberMapsforgeController
import de.ledgerline.app.ui.workspace.LocalFullscreen
import kotlinx.coroutines.launch

/** Berlin — a sensible default center when the target photo has no coordinates. */
private const val DEFAULT_LAT = 52.520008
private const val DEFAULT_LNG = 13.404954

/**
 * Full-screen location picker: a tappable mapsforge map that drops/moves a marker, plus a
 * Nominatim address search that recenters the map + moves the marker. The confirm FAB returns
 * the marker's lat/lng. Mirrors the web `openLocPicker`/`geoSearch`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerScreen(
    initialLat: Double?,
    initialLng: Double?,
    onPick: (Double, Double) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Forward-geocode via the ZK server proxy (GalleryViewModel.geocode) — never third-party-direct. */
    onSearch: suspend (String) -> Pair<Double, Double>?,
) {
    BackHandler(onBack = onBack)
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }

    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    val startLat = initialLat ?: DEFAULT_LAT
    val startLng = initialLng ?: DEFAULT_LNG

    var pickedLat by rememberSaveable { mutableStateOf(startLat) }
    var pickedLng by rememberSaveable { mutableStateOf(startLng) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var notFound by rememberSaveable { mutableStateOf(false) }

    val controller = rememberMapsforgeController()
    val pin = rememberMapPin()

    fun moveMarker(lat: Double, lng: Double, recenter: Boolean) {
        pickedLat = lat
        pickedLng = lng
        controller.setMarkers(listOf(lat to lng), pin)
        if (recenter) controller.moveTo(lat, lng)
    }

    LaunchedEffect(pin) { controller.setMarkers(listOf(pickedLat to pickedLng), pin) }

    fun doSearch() {
        val q = query.trim()
        if (q.isBlank()) return
        keyboard?.hide()
        searching = true
        notFound = false
        scope.launch {
            val hit = onSearch(q)
            searching = false
            if (hit == null) notFound = true else { notFound = false; moveMarker(hit.first, hit.second, recenter = true) }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_set_location)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.loc_confirm)) },
                icon = { Icon(Icons.Outlined.Check, contentDescription = null) },
                onClick = { onPick(pickedLat, pickedLng) },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            GatedMapsforgeMap(
                modifier = Modifier.fillMaxSize(),
                controller = controller,
                initialLat = startLat,
                initialLng = startLng,
                initialZoom = 14,
                onMapTap = { la, ln -> moveMarker(la, ln, recenter = false) },
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it; notFound = false },
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.loc_search_hint)) },
                leadingIcon = {
                    if (searching) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(4.dp))
                    else Icon(Icons.Outlined.Search, contentDescription = null)
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
