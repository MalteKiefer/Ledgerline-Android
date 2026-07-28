package de.ledgerline.app.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import de.ledgerline.app.core.tracker.ActivityKind
import de.ledgerline.app.core.tracker.RecordingState
import de.ledgerline.app.core.units.MeasureFormatter
import de.ledgerline.app.core.units.UnitSystem
import de.ledgerline.app.domain.model.ExploreTrack
import de.ledgerline.app.ui.common.LedgerRow
import de.ledgerline.app.ui.common.ListBottomPadding
import de.ledgerline.app.ui.common.RowChevron
import de.ledgerline.app.ui.common.SoftIconChip
import de.ledgerline.app.ui.common.listSection
import de.ledgerline.app.ui.map.MapsforgeMap
import de.ledgerline.app.ui.map.rememberMapsforgeController
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.PrimaryGradientButton

/**
 * Karte tab: mapsforge viewer with place **search** (forward geocode → recenter), a reverse-
 * geocoded place chip with **share** (geo: URI + coordinates, iOS `LocationShare` parity), a
 * **locate** FAB, a **compass** + device-**heading** orientation, and a **tour-planning** mode
 * (tap waypoints → snap via `/maps/route` → save as a planned track). Network features (reverse
 * geocode, search, routing) run only when online tiles are enabled.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun MapPane(vm: ExploreViewModel, onlineEnabled: Boolean, controller: de.ledgerline.app.ui.map.MapsforgeController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val offline = remember { vm.offlineMaps() }
    val pin = de.ledgerline.app.ui.map.rememberMapPin()
    val waypoints by vm.waypoints.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    var planning by remember { mutableStateOf(false) }
    var place by remember { mutableStateOf<String?>(null) }
    var showSave by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var heading by remember { mutableStateOf(false) }
    var bearing by remember { mutableStateOf(0f) }
    var suggestion by remember { mutableStateOf<de.ledgerline.app.core.map.OfflineMapRegion?>(null) }

    // Camera center flows through a non-Compose StateFlow so panning/zooming does NOT recompose
    // this pane every frame (that made the map feel laggy). Reverse-geocode debounces on settle.
    val centerFlow = remember { kotlinx.coroutines.flow.MutableStateFlow<Pair<Double, Double>?>(null) }
    androidx.compose.runtime.LaunchedEffect(onlineEnabled, planning) {
        centerFlow.debounce(800L).collectLatest { c ->
            if (c != null && onlineEnabled && !planning) {
                val resp = vm.reverseAddress(c.first, c.second)
                place = resp?.place
                suggestion = resp?.address?.let { vm.suggestRegion(it) }
            } else { place = null; suggestion = null }
        }
    }
    // Terrain relief: when enabled, download DEM tiles for the settled viewport.
    val terrain by vm.terrain.collectAsStateWithLifecycle()
    val demVersion by vm.demVersion.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(terrain) {
        if (terrain) centerFlow.debounce(1200L).collectLatest {
            controller.visibleBounds()?.let { vm.ensureDem(it[0], it[1], it[2], it[3]) }
        }
    }

    // Stable tap handler so the map's tap layer isn't rebuilt on every recomposition.
    val onTap: ((Double, Double) -> Unit)? = remember(planning) {
        if (planning) { la: Double, ln: Double -> vm.addWaypoint(la, ln) } else null
    }
    androidx.compose.runtime.LaunchedEffect(waypoints, route, pin, planning) {
        if (planning) {
            controller.setMarkers(waypoints, pin)
            controller.setTrack(route.ifEmpty { waypoints })
        } else {
            controller.setTrack(emptyList())
        }
    }
    // Device-heading orientation: rotate the map so the direction the user faces is up.
    HeadingSensor(enabled = heading) { az ->
        val b = ((-az % 360) + 360) % 360
        if (kotlin.math.abs(b - bearing) > 1.5f) { bearing = b; controller.setRotation(b) }
    }

    fun showAt(lat: Double, lng: Double) {
        controller.moveTo(lat, lng, 15)
        controller.setSearchPin(lat, lng, pin) // show the located position on the map
    }
    fun centerOnLastKnown() = de.ledgerline.app.core.map.LocationOnce.current(context) { la, ln -> showAt(la, ln) }
    val locateLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> if (grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) centerOnLastKnown() }

    // Auto-find the position when the Karte opens (if permission is already granted).
    androidx.compose.runtime.LaunchedEffect(Unit) { centerOnLastKnown() }

    fun shareCurrent() {
        val c = centerFlow.value ?: return
        val la = "%.6f".format(java.util.Locale.US, c.first)
        val ln = "%.6f".format(java.util.Locale.US, c.second)
        val label = place?.let { "($it)" } ?: ""
        val text = buildString {
            place?.let { append(it).append('\n') }
            append("$la, $ln\n")
            append("geo:$la,$ln?q=$la,$ln$label")
        }
        context.startActivity(de.ledgerline.app.ui.common.shareTextChooser(context, text))
    }

    Box(Modifier.fillMaxSize()) {
        MapsforgeMap(
            modifier = Modifier.fillMaxSize(),
            controller = controller,
            offlineMaps = offline,
            onlineEnabled = onlineEnabled,
            hillshading = terrain,
            demFolder = vm.demFolder(),
            demVersion = demVersion,
            onCenterChanged = { la, ln -> centerFlow.value = la to ln },
            onMapTap = onTap,
        )

        // Top: reverse-geocoded place chip + smart download offer (search lives in the header).
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!planning && !place.isNullOrBlank()) {
                Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 4.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(place!!, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { shareCurrent() }) {
                            Icon(androidx.compose.material.icons.Icons.Outlined.Share, contentDescription = stringResource(R.string.action_share))
                        }
                    }
                }
            }
            // Smart offer: this area's offline map isn't downloaded → offer it.
            suggestion?.let { region ->
                if (!planning) {
                    Surface(
                        onClick = { vm.downloadRegion(region); suggestion = null },
                        shape = MaterialTheme.shapes.large,
                        color = Brand.accent,
                        shadowElevation = 4.dp,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(androidx.compose.material.icons.Icons.Outlined.Download, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.explore_download_area, region.name),
                                style = MaterialTheme.typography.labelLarge,
                                color = androidx.compose.ui.graphics.Color.White,
                            )
                        }
                    }
                }
            }
        }

        // Compass (top-end): visible when the map is rotated; tap resets to north.
        if (bearing != 0f) {
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = { heading = false; bearing = 0f; controller.resetNorth() },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).padding(top = 76.dp),
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Outlined.Navigation,
                    contentDescription = stringResource(R.string.explore_compass),
                    modifier = Modifier.rotate(-bearing),
                )
            }
        }

        // Right-side action stack: plan · heading · locate.
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp).padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.SmallFloatingActionButton(onClick = { planning = !planning; if (!planning) vm.clearPlan() }) {
                Icon(if (planning) Icons.Filled.Close else Icons.Outlined.Route, contentDescription = stringResource(R.string.explore_plan))
            }
            androidx.compose.material3.SmallFloatingActionButton(onClick = {
                heading = !heading
                if (!heading) { bearing = 0f; controller.resetNorth() } else centerOnLastKnown()
            }) {
                Icon(
                    androidx.compose.material.icons.Icons.Outlined.Explore,
                    contentDescription = stringResource(R.string.explore_heading),
                    tint = if (heading) Brand.accent else androidx.compose.material3.LocalContentColor.current,
                )
            }
            androidx.compose.material3.SmallFloatingActionButton(onClick = {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) centerOnLastKnown()
                else locateLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
            }) {
                Icon(Icons.Outlined.MyLocation, contentDescription = stringResource(R.string.explore_locate))
            }
        }

        // Planning control bar (bottom, above the tab pill).
        if (planning) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 78.dp).padding(horizontal = 12.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) {
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { vm.undoWaypoint() }, enabled = waypoints.isNotEmpty()) { Text(stringResource(R.string.explore_undo)) }
                    TextButton(onClick = { vm.snapRoute() }, enabled = waypoints.size >= 2 && onlineEnabled) { Text(stringResource(R.string.explore_route)) }
                    TextButton(onClick = { showSave = true }, enabled = waypoints.size >= 2) { Text(stringResource(R.string.explore_save)) }
                }
            }
        }
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text(stringResource(R.string.explore_save_title)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.explore_name_hint)) }) },
            confirmButton = { TextButton(onClick = { vm.savePlanned(name) { showSave = false; name = ""; planning = false } }) { Text(stringResource(R.string.explore_save)) } },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Registers a rotation-vector sensor listener while [enabled], reporting the device azimuth (°). */
@Composable
private fun HeadingSensor(enabled: Boolean, onHeading: (Float) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : android.hardware.SensorEventListener {
            private val rot = FloatArray(9)
            private val orient = FloatArray(3)
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(rot, e.values)
                android.hardware.SensorManager.getOrientation(rot, orient)
                onHeading(((Math.toDegrees(orient[0].toDouble()).toFloat() % 360) + 360) % 360)
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
}

@Composable
fun TrackerPane(vm: ExploreViewModel, onlineEnabled: Boolean, onRequestPermission: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val UNIT by vm.unit.collectAsStateWithLifecycle()
    val elevFeet by vm.elevationFeet.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = rememberMapsforgeController()
    val offline = remember { vm.offlineMaps() }
    val pin = de.ledgerline.app.ui.map.rememberMapPin()
    var showSave by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(ui.points.size) {
        controller.setTrack(ui.points.map { it.lat to it.lng })
        ui.points.lastOrNull()?.let { controller.moveTo(it.lat, it.lng); controller.setSearchPin(it.lat, it.lng, pin) }
    }
    // Auto-find + show the current position when the Tracker opens (before recording starts).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (ui.points.isEmpty()) de.ledgerline.app.core.map.LocationOnce.current(context) { la, ln ->
            controller.moveTo(la, ln, 15); controller.setSearchPin(la, ln, pin)
        }
    }

    Box(Modifier.fillMaxSize()) {
        MapsforgeMap(
            modifier = Modifier.fillMaxSize(),
            controller = controller,
            offlineMaps = offline,
            onlineEnabled = onlineEnabled,
        )

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp).padding(bottom = 76.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Stats HUD
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val s = ui.stats
                    StatCell(stringResource(R.string.stat_distance), MeasureFormatter.distance(s?.distanceM ?: 0.0, UNIT))
                    StatCell(stringResource(R.string.stat_duration), MeasureFormatter.duration(ui.elapsedMs / 1000.0))
                    if (ui.activity == ActivityKind.CYCLE) {
                        StatCell(stringResource(R.string.stat_speed), MeasureFormatter.speed(s?.avgSpeedMps ?: 0.0, UNIT))
                    } else {
                        StatCell(stringResource(R.string.stat_pace), MeasureFormatter.pace(s?.avgSpeedMps ?: 0.0, UNIT))
                    }
                    StatCell(stringResource(R.string.stat_ascent), MeasureFormatter.elevation(s?.ascentM ?: 0.0, elevFeet))
                }

                if (ui.state == RecordingState.IDLE) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ActivityKind.entries.forEachIndexed { i, a ->
                            SegmentedButton(
                                selected = ui.activity == a,
                                onClick = { vm.setActivity(a) },
                                shape = SegmentedButtonDefaults.itemShape(i, ActivityKind.entries.size),
                                icon = { Icon(activityIcon(a), null) },
                            ) { Text(stringResource(activityLabel(a))) }
                        }
                    }
                }

                // Controls
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (ui.state) {
                        RecordingState.IDLE -> PrimaryGradientButton(
                            text = stringResource(R.string.explore_start),
                            onClick = { if (vm.hasLocationPermission()) vm.start(ui.activity) else onRequestPermission() },
                            modifier = Modifier.weight(1f),
                        )
                        RecordingState.RECORDING -> {
                            TextButton(onClick = { vm.pause() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Pause, null); Text(stringResource(R.string.explore_pause))
                            }
                            PrimaryGradientButton(
                                text = stringResource(R.string.explore_stop),
                                onClick = { vm.stop(); if (ui.points.size >= 2) showSave = true else { vm.discard(); android.widget.Toast.makeText(context, context.getString(R.string.explore_track_empty), android.widget.Toast.LENGTH_LONG).show() } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        RecordingState.PAUSED -> {
                            TextButton(onClick = { vm.resume() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.PlayArrow, null); Text(stringResource(R.string.explore_resume))
                            }
                            PrimaryGradientButton(
                                text = stringResource(R.string.explore_stop),
                                onClick = { vm.stop(); if (ui.points.size >= 2) showSave = true else { vm.discard(); android.widget.Toast.makeText(context, context.getString(R.string.explore_track_empty), android.widget.Toast.LENGTH_LONG).show() } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text(stringResource(R.string.explore_save_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.explore_name_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.save(name) { showSave = false; name = "" } }) {
                    Text(stringResource(R.string.explore_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false; name = ""; vm.discard() }) {
                    Text(stringResource(R.string.explore_discard))
                }
            },
        )
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TracksPane(vm: ExploreViewModel, onOpen: (String) -> Unit) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.explore_no_tracks), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
        listSection(tracks, key = { it.id }) { track ->
            LedgerRow(
                title = track.name,
                subtitle = trackSubtitle(track, unit),
                leading = { SoftIconChip(activityIcon(activityOf(track)), tint = Brand.tintTeal) },
                trailing = { TrackOverflow(onDelete = { vm.deleteTrack(track.id) }) },
                onClick = { onOpen(track.id) },
            )
        }
    }
}

@Composable
private fun TrackOverflow(onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, null) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { open = false; onDelete() })
        }
    }
}

private fun trackSubtitle(t: ExploreTrack, unit: UnitSystem): String {
    val dist = MeasureFormatter.distance(t.stats?.distanceM ?: 0.0, unit)
    val dur = MeasureFormatter.duration(t.stats?.durationTotalS ?: 0.0)
    return "$dist · $dur"
}

private fun activityOf(t: ExploreTrack): ActivityKind = when (t.activity) {
    "run" -> ActivityKind.RUN
    "cycle" -> ActivityKind.CYCLE
    else -> ActivityKind.HIKE
}

private fun activityIcon(a: ActivityKind) = when (a) {
    ActivityKind.HIKE -> Icons.Outlined.DirectionsWalk
    ActivityKind.RUN -> Icons.Outlined.DirectionsRun
    ActivityKind.CYCLE -> Icons.Outlined.DirectionsBike
}

private fun activityLabel(a: ActivityKind): Int = when (a) {
    ActivityKind.HIKE -> R.string.act_hike
    ActivityKind.RUN -> R.string.act_run
    ActivityKind.CYCLE -> R.string.act_cycle
}
