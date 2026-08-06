package de.ledgerline.app.ui.explore

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import kotlinx.coroutines.launch
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.map.MapTilesViewModel
import de.ledgerline.app.ui.map.MapsforgeMap
import de.ledgerline.app.ui.workspace.common.FloatingTabBar

/** The three Explore sub-views, switched via the floating pill. */
enum class ExploreTab { MAP, TRACKER, TRACKS }

/**
 * Explore hub: a passive **Karte** (map viewer), the **Tracker** (GPS recording), and the saved
 * **Tracks** list. Offline mapsforge vector maps render whenever a region is downloaded; the
 * online OSM fallback only fires once the user has opted into map tiles (the existing gate).
 */
@Composable
fun ExploreScreen(
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    vm: ExploreViewModel = hiltViewModel(),
    tilesVm: MapTilesViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(ExploreTab.MAP) }
    var selectedTrackId by remember { mutableStateOf<String?>(null) }
    var showWorldOffer by remember { mutableStateOf(false) }
    val onlineEnabled by tilesVm.enabled.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()
    val worldOffered by vm.worldMapOffered.collectAsStateWithLifecycle()

    // One-time offer: the tiny world base map so the map always shows something offline.
    androidx.compose.runtime.LaunchedEffect(worldOffered) {
        if (!worldOffered && !vm.worldMapInstalled()) showWorldOffer = true
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) vm.start(vm.ui.value.activity)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val rawBytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val text = rawBytes?.toString(Charsets.UTF_8)
            val fileName = uri.lastPathSegment ?: "track.gpx"
            val parsed = text?.let { de.ledgerline.app.core.explore.TrackImport.parse(it, fileName) }
            if (parsed != null) {
                vm.importParsed(parsed.name, parsed.sourceFormat, parsed.points, rawBytes) { ok ->
                    if (ok) tab = ExploreTab.TRACKS
                }
            }
        }
    }

    // Map search (header lupe → expanding modal field): forward-geocode + drop a search pin.
    val mapController = de.ledgerline.app.ui.map.rememberMapsforgeController()
    val searchPin = de.ledgerline.app.ui.map.rememberMapPin()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    fun runSearch() {
        val qq = query.trim()
        if (qq.isBlank()) return
        searching = true
        scope.launch {
            val hit = vm.geocode(qq)
            searching = false
            hit?.let { mapController.setSearchPin(it.first, it.second, searchPin); mapController.moveTo(it.first, it.second, 14) }
            searchOpen = false
        }
    }

    selectedTrackId?.let { id ->
        val track = tracks.firstOrNull { it.id == id }
        if (track == null) { selectedTrackId = null } else {
            TrackDetailScreen(
                track = track,
                unit = unit,
                elevationFeet = vm.elevationFeet.collectAsStateWithLifecycle().value,
                calories = vm.caloriesFor(track),
                onBack = { selectedTrackId = null },
                onDelete = { vm.deleteTrack(id); selectedTrackId = null },
                onFetchRaw = { vm.rawFileBytes(track) },
            )
            return
        }
    }

    androidx.compose.material3.Scaffold(
        modifier = modifier,
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                AppTopBar(
                    title = stringResource(R.string.dest_explore),
                    onMenu = onMenu,
                    actions = {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(androidx.compose.material.icons.Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                        if (tab == ExploreTab.MAP) {
                            IconButton(onClick = { searchOpen = !searchOpen }) {
                                Icon(
                                    androidx.compose.material.icons.Icons.Outlined.Search,
                                    contentDescription = stringResource(R.string.gallery_search_hint),
                                    tint = if (searchOpen) de.ledgerline.app.ui.theme.Brand.accent else androidx.compose.material3.LocalContentColor.current,
                                )
                            }
                        }
                        IconButton(onClick = { importLauncher.launch(arrayOf("application/gpx+xml", "application/vnd.google-earth.kml+xml", "application/xml", "text/xml", "*/*")) }) {
                            Icon(androidx.compose.material.icons.Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.explore_import))
                        }
                    },
                )
                // Expanding search field (opens from the header lupe).
                if (searchOpen && tab == ExploreTab.MAP) {
                    androidx.compose.material3.OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        singleLine = true,
                        placeholder = { androidx.compose.material3.Text(stringResource(R.string.loc_search_hint)) },
                        leadingIcon = {
                            if (searching) androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(4.dp).size(18.dp))
                            else Icon(androidx.compose.material.icons.Icons.Outlined.Search, null)
                        },
                        trailingIcon = {
                            IconButton(onClick = { searchOpen = false; query = "" }) {
                                Icon(androidx.compose.material.icons.Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel))
                            }
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { runSearch() }),
                    )
                }
                // Active offline-map download progress (world base map / region downloads).
                val downloads by vm.mapDownloads.collectAsStateWithLifecycle()
                downloads.entries.firstOrNull { it.value is de.ledgerline.app.core.map.MapDownloadState.Downloading }?.let { entry ->
                    val d = entry.value as de.ledgerline.app.core.map.MapDownloadState.Downloading
                    if (d.totalBytes > 0) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (d.receivedBytes.toFloat() / d.totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Text(
                            "${vm.regionName(entry.key) ?: ""} · ${de.ledgerline.app.ui.workspace.common.humanSize(d.receivedBytes)}",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.TextButton(onClick = { vm.cancelDownload(entry.key) }) {
                            androidx.compose.material3.Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                ExploreTab.MAP -> MapPane(vm = vm, onlineEnabled = onlineEnabled, controller = mapController)
                ExploreTab.TRACKER -> TrackerPane(
                    vm = vm,
                    onlineEnabled = onlineEnabled,
                    onRequestPermission = {
                        permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                )
                ExploreTab.TRACKS -> TracksPane(vm = vm, onOpen = { selectedTrackId = it })
            }
            FloatingTabBar(
                tabs = listOf(
                    stringResource(R.string.explore_tab_map),
                    stringResource(R.string.explore_tab_tracker),
                    stringResource(R.string.explore_tab_tracks),
                ),
                selectedIndex = tab.ordinal,
                onSelect = { tab = ExploreTab.entries[it] },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showWorldOffer) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showWorldOffer = false; vm.markWorldMapOffered() },
            title = { androidx.compose.material3.Text(stringResource(R.string.world_map_offer_title)) },
            text = { androidx.compose.material3.Text(stringResource(R.string.world_map_offer_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.downloadWorldMap(); vm.markWorldMapOffered(); showWorldOffer = false
                }) { androidx.compose.material3.Text(stringResource(R.string.world_map_offer_download)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.markWorldMapOffered(); showWorldOffer = false }) {
                    androidx.compose.material3.Text(stringResource(R.string.action_later))
                }
            },
        )
    }
}
