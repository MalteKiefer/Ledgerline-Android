package de.ledgerline.app.ui.explore

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.ledgerline.app.R
import de.ledgerline.app.core.explore.ElevationSample
import de.ledgerline.app.core.explore.GpxWriter
import de.ledgerline.app.core.explore.TrackGeometry
import de.ledgerline.app.core.units.MeasureFormatter
import de.ledgerline.app.core.units.UnitSystem
import de.ledgerline.app.domain.model.ExploreTrack
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.ListSectionCard
import de.ledgerline.app.ui.map.GatedMapsforgeMap
import de.ledgerline.app.ui.map.rememberMapsforgeController
import de.ledgerline.app.ui.theme.Brand
import org.mapsforge.core.graphics.Bitmap as MapBitmap
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

/**
 * Read-only detail view for one saved Explore/Tracker [track]: a fitted map with the
 * recorded polyline, a grouped statistics grid and an interactive Compose-drawn elevation
 * profile whose horizontal drag drives a hover marker on the map (mirrors iOS
 * `TrackDetailView`). GPX export is offered from the top bar; deletion from its overflow.
 *
 * Distances/elevations/speeds are formatted with [MeasureFormatter] in the caller-chosen
 * [unit]. Robust to `FLAG_SECURE` (no screenshots): the layout scrolls, the map and chart
 * have fixed heights, and the chart is hidden when the track carries no elevation samples.
 */
@Composable
fun TrackDetailScreen(
    track: ExploreTrack,
    unit: UnitSystem,
    elevationFeet: Boolean,
    calories: Long?,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val controller = rememberMapsforgeController()

    // A small mapsforge marker bitmap reused for the elevation-hover highlight on the map.
    val hoverBitmap: MapBitmap? = remember {
        ContextCompat.getDrawable(context, R.drawable.ic_map_pin)
            ?.let { runCatching { AndroidGraphicFactory.convertToBitmap(it) }.getOrNull() }
    }

    // Draw the polyline and fit the camera once the map surface is attached.
    LaunchedEffect(track.id) {
        val coords = track.points.map { it.lat to it.lng }
        controller.setTrack(coords)
        // Direction-of-travel arrows along the route (web parity).
        controller.setDirectionArrows(de.ledgerline.app.core.explore.TrackArrows.compute(track.points), de.ledgerline.app.ui.theme.Brand.accent.toArgb())
        val bb = track.bbox
        if (bb != null) {
            controller.fitBounds(bb.minLat, bb.minLng, bb.maxLat, bb.maxLng)
        } else if (coords.isNotEmpty()) {
            val minLat = coords.minOf { it.first }
            val maxLat = coords.maxOf { it.first }
            val minLng = coords.minOf { it.second }
            val maxLng = coords.maxOf { it.second }
            controller.fitBounds(minLat, minLng, maxLat, maxLng)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = track.name,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { shareGpx(context, track) }) {
                        Icon(
                            Icons.Outlined.IosShare,
                            contentDescription = stringResource(R.string.track_export_gpx),
                        )
                    }
                    TrackDetailOverflow(onDelete = onDelete)
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // Fitted map (~40% of a phone screen) — fixed height keeps the scroll robust.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp),
            ) {
                GatedMapsforgeMap(
                    modifier = Modifier.fillMaxSize(),
                    controller = controller,
                )
            }

            ElevationProfileSection(
                track = track,
                unit = unit,
                elevationFeet = elevationFeet,
                onHover = { distM ->
                    val coord = TrackGeometry.interpolateAtDistance(track.points, distM)
                    controller.setMarkers(coord?.let { listOf(it) } ?: emptyList(), hoverBitmap)
                },
            )

            StatsGrid(track = track, unit = unit, elevationFeet = elevationFeet, calories = calories)

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TrackDetailOverflow(onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.menu_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

/**
 * Interactive elevation-vs-distance chart, drawn with a Compose [Canvas] (no chart lib).
 * Fills under the line with a translucent brand gradient. A horizontal drag maps the touch
 * x → cumulative distance → elevation, shows a readout and calls [onHover] with that distance
 * so the caller can drop a marker on the map. Hidden when there is no usable elevation data.
 */
@Composable
private fun ElevationProfileSection(
    track: ExploreTrack,
    unit: UnitSystem,
    elevationFeet: Boolean,
    onHover: (Double) -> Unit,
) {
    val profile: List<ElevationSample> = remember(track.id) {
        track.stats?.elevationProfile?.filter { it.eleM != null } ?: emptyList()
    }
    if (profile.size < 2) return

    val minX = remember(profile) { profile.minOf { it.distM } }
    val maxX = remember(profile) { profile.maxOf { it.distM } }
    val minY = remember(profile) { profile.minOf { it.eleM!! } }
    val maxY = remember(profile) { profile.maxOf { it.eleM!! } }
    val spanX = (maxX - minX).takeIf { it > 0.0 } ?: 1.0
    val spanY = (maxY - minY).takeIf { it > 0.0 } ?: 1.0

    // Hover fraction along the x-axis (0..1); null when not touching.
    var hoverFrac by remember(track.id) { mutableStateOf<Float?>(null) }
    var readout by remember(track.id) { mutableStateOf<String?>(null) }

    fun elevationAt(distM: Double): Double {
        // Nearest sample by cumulative distance (samples are monotonic in distM).
        var best = profile.first()
        var bestD = Double.MAX_VALUE
        for (s in profile) {
            val d = kotlin.math.abs(s.distM - distM)
            if (d < bestD) { bestD = d; best = s }
        }
        return best.eleM!!
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        // Live readout above the chart (distance · elevation at the drag point).
        Text(
            readout ?: " ",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Brand.accent,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        )

        val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .pointerInput(track.id) {
                    fun update(x: Float) {
                        val frac = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        hoverFrac = frac
                        val distM = minX + frac * (maxX - minX)
                        val ele = elevationAt(distM)
                        readout = MeasureFormatter.distance(distM, unit) +
                            "  ·  " + MeasureFormatter.elevation(ele, elevationFeet)
                        onHover(distM)
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { update(it.x) },
                    ) { change, _ -> update(change.position.x) }
                },
        ) {
            val w = size.width
            val h = size.height
            val effH = (h - padPx * 2).coerceAtLeast(1f)
            fun sx(d: Double): Float = ((d - minX) / spanX).toFloat() * w
            fun sy(e: Double): Float = padPx + (1f - ((e - minY) / spanY).toFloat()) * effH

            val line = Path().apply {
                profile.forEachIndexed { i, s ->
                    val x = sx(s.distM); val y = sy(s.eleM!!)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            // Fill under the line down to the baseline.
            val fill = Path().apply {
                addPath(line)
                lineTo(sx(profile.last().distM), h)
                lineTo(sx(profile.first().distM), h)
                close()
            }
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    listOf(Brand.accent.copy(alpha = 0.35f), Brand.accent.copy(alpha = 0.02f)),
                ),
            )
            drawPath(path = line, color = Brand.accent, style = Stroke(width = 3.dp.toPx()))

            hoverFrac?.let { frac ->
                val x = frac * w
                val distM = minX + frac * (maxX - minX)
                val y = sy(elevationAt(distM))
                drawLine(
                    color = Brand.accent.copy(alpha = 0.5f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawCircle(color = Brand.accent, radius = 4.dp.toPx(), center = Offset(x, y))
            }
        }
    }
}

/** Two-column grid of labelled stat cells in a single grouped card. */
@Composable
private fun StatsGrid(track: ExploreTrack, unit: UnitSystem, elevationFeet: Boolean, calories: Long?) {
    val s = track.stats
    val cells = buildList {
        add(stringResource(R.string.stat_distance) to MeasureFormatter.distance(s?.distanceM ?: 0.0, unit))
        add(stringResource(R.string.track_stat_total_time) to MeasureFormatter.duration(s?.durationTotalS ?: 0.0))
        add(stringResource(R.string.track_stat_moving_time) to MeasureFormatter.duration(s?.durationMovingS ?: 0.0))
        add(stringResource(R.string.stat_ascent) to MeasureFormatter.elevation(s?.ascentM ?: 0.0, elevationFeet))
        add(stringResource(R.string.track_stat_descent) to MeasureFormatter.elevation(s?.descentM ?: 0.0, elevationFeet))
        s?.minEleM?.let { add(stringResource(R.string.track_stat_min_ele) to MeasureFormatter.elevation(it, elevationFeet)) }
        s?.maxEleM?.let { add(stringResource(R.string.track_stat_max_ele) to MeasureFormatter.elevation(it, elevationFeet)) }
        val avg = if (track.activity == "cycle") {
            MeasureFormatter.speed(s?.avgSpeedMps ?: 0.0, unit)
        } else {
            MeasureFormatter.pace(s?.avgSpeedMps ?: 0.0, unit)
        }
        add(stringResource(R.string.track_stat_avg) to avg)
        add(stringResource(R.string.track_stat_max_speed) to MeasureFormatter.speed(s?.maxSpeedMps ?: 0.0, unit))
        calories?.let { add(stringResource(R.string.stat_calories) to stringResource(R.string.stat_calories_value, it.toInt())) }
    }

    ListSectionCard {
        Column(Modifier.padding(vertical = 6.dp)) {
            cells.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth()) {
                    StatCell(pair[0].first, pair[0].second, Modifier.weight(1f))
                    if (pair.size > 1) {
                        StatCell(pair[1].first, pair[1].second, Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Share the track as GPX. No `FileProvider` is declared in the manifest, so we fall back to
 * an `ACTION_SEND` with the GPX document in `EXTRA_TEXT` (typed `application/gpx+xml`).
 */
private fun shareGpx(context: android.content.Context, track: ExploreTrack) {
    val gpx = GpxWriter.write(track.name, track.points)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_TITLE, "${track.name}.gpx")
        putExtra(Intent.EXTRA_SUBJECT, "${track.name}.gpx")
        putExtra(Intent.EXTRA_TEXT, gpx)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.track_export_gpx))
    runCatching { context.startActivity(chooser) }
}
