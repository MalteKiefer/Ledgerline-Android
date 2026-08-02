package de.ledgerline.app.ui.health

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.core.health.HealthCompute
import de.ledgerline.app.core.health.HealthFasting
import de.ledgerline.app.core.health.HealthMetrics
import de.ledgerline.app.domain.model.HealthEntry
import de.ledgerline.app.domain.model.HealthFast
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.cardSurface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Reference-status dot colours (clinical traffic-light). */
private val StatusColors = mapOf(
    HealthMetrics.Status.OK to Color(0xFF59AD6B),
    HealthMetrics.Status.AMBER to Color(0xFFE2915A),
    HealthMetrics.Status.RED to Color(0xFFF43F5E),
)

private val RANGES = listOf("7d", "30d", "90d", "1y", "all")

/**
 * The Health module: a metric selector, per-metric chart + stats + entry list, an intermittent
 * fasting tracker, and body-data (master) with derived age/BMI. Everything is sealed in
 * `store/health`; measurements are entered in display units and stored canonically.
 */
@Composable
fun HealthScreen(
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    vm: HealthViewModel = hiltViewModel(),
) {
    val manifest by vm.manifest.collectAsStateWithLifecycle()
    val units by vm.units.collectAsStateWithLifecycle()
    val selected by vm.selectedMetric.collectAsStateWithLifecycle()
    val range by vm.chartRange.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val is12h by vm.is12h.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var editorEntry by remember { mutableStateOf<HealthEntry?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var masterOpen by remember { mutableStateOf(false) }
    var fastEditor by remember { mutableStateOf<HealthFast?>(null) }
    var confirmDeleteEntry by remember { mutableStateOf<HealthEntry?>(null) }
    var confirmStopFast by remember { mutableStateOf<HealthFast?>(null) }
    var confirmDeleteFast by remember { mutableStateOf<HealthFast?>(null) }

    // Surface VM toasts (fast-already-running / invalid) as Android toasts, once per emission.
    val alreadyRunning = stringResource(R.string.health_fast_already_running)
    val invalidFast = stringResource(R.string.health_fast_invalid)
    androidx.compose.runtime.LaunchedEffect(toast) {
        val key = toast ?: return@LaunchedEffect
        val msg = when (key) {
            "fast_already_running" -> alreadyRunning
            "fast_invalid" -> invalidFast
            else -> key
        }
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        vm.consumeToast()
    }

    AppScaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.dest_health),
                onMenu = onMenu,
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(androidx.compose.material.icons.Icons.Outlined.Refresh, stringResource(R.string.action_refresh))
                    }
                    IconButton(onClick = { shareCsv(context, HealthCompute.csv(manifest.entries, selected, units), selected) }) {
                        Icon(Icons.Outlined.IosShare, stringResource(R.string.health_export_csv))
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            de.ledgerline.app.ui.common.PullRefresh(onRefresh = { vm.load() }) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                MetricSelectorRow(manifest.entries, selected, onSelect = vm::select)
                MetricDetailCard(
                    key = selected,
                    entries = manifest.entries,
                    units = units,
                    range = range,
                    nowMs = now,
                    is12h = is12h,
                    weightGoalKg = manifest.profile.weightGoalKg,
                    onRange = vm::setRange,
                    onEdit = { editorEntry = it; editorOpen = true },
                    onDelete = { confirmDeleteEntry = it },
                )
                FastingCard(
                    active = HealthFasting.activeFast(manifest.fasts),
                    history = manifest.fasts.filter { !it.end.isNullOrEmpty() }.sortedByDescending { it.start },
                    nowMs = now,
                    is12h = is12h,
                    onStart = vm::startFast,
                    onStop = { confirmStopFast = it },
                    onEditFast = { fastEditor = it },
                    onDeleteFast = { confirmDeleteFast = it },
                )
                MasterDataCard(
                    age = vm.age(),
                    bmi = vm.bmi(),
                    profile = manifest.profile,
                    onEdit = { masterOpen = true },
                )
                Text(
                    stringResource(R.string.health_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            }

            FloatingActionButton(
                onClick = { editorEntry = null; editorOpen = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) { Icon(Icons.Outlined.Add, stringResource(R.string.health_add)) }
        }
    }

    if (editorOpen) {
        MeasurementEditorSheet(
            initialMetric = editorEntry?.metric ?: selected,
            editing = editorEntry,
            units = units,
            onDismiss = { editorOpen = false },
            onSave = { metric, v, v2, ts, note -> vm.saveEntry(metric, v, v2, ts, note, editorEntry) },
        )
    }

    if (masterOpen) {
        MasterDataSheet(
            profile = manifest.profile,
            onDismiss = { masterOpen = false },
            onSave = { updated -> vm.saveProfile { updated.copy(units = it.units, raw = it.raw) }; masterOpen = false },
        )
    }

    fastEditor?.let { f ->
        FastEditorSheet(
            fast = f,
            onDismiss = { fastEditor = null },
            onSave = { start, end, target, note -> if (vm.saveFastEdit(f, start, end, target, note)) fastEditor = null },
        )
    }

    confirmDeleteEntry?.let { e ->
        ConfirmDialog(
            text = stringResource(R.string.health_delete_confirm),
            onConfirm = { vm.deleteEntry(e); confirmDeleteEntry = null },
            onDismiss = { confirmDeleteEntry = null },
        )
    }
    confirmStopFast?.let { f ->
        ConfirmDialog(
            text = stringResource(R.string.health_fast_stop_confirm),
            onConfirm = { vm.stopFast(f); confirmStopFast = null },
            onDismiss = { confirmStopFast = null },
        )
    }
    confirmDeleteFast?.let { f ->
        ConfirmDialog(
            text = stringResource(R.string.health_fast_delete_confirm),
            onConfirm = { vm.deleteFast(f); confirmDeleteFast = null },
            onDismiss = { confirmDeleteFast = null },
        )
    }
}

// ---- metric selector -------------------------------------------------------

@Composable
private fun MetricSelectorRow(entries: List<HealthEntry>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HealthMetrics.METRICS.forEach { m ->
            val status = HealthCompute.classifyLatest(entries, m.key)
            val latest = HealthCompute.latest(entries, m.key)
            MetricChip(
                label = metricLabel(m.key),
                tint = Color(m.tint),
                selected = m.key == selected,
                latest = latest?.let { HealthCompute.displayValue(m.key, it.v, it.v2, de.ledgerline.app.domain.model.HealthUnits()) },
                status = if (latest != null) status else null,
                onClick = { onSelect(m.key) },
            )
        }
    }
}

@Composable
private fun MetricChip(label: String, tint: Color, selected: Boolean, latest: String?, status: HealthMetrics.Status?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Brand.cardRadius)
    val borderColor = if (selected) tint else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    Column(
        Modifier
            .width(112.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(if (selected) 2.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(6.dp))
            status?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(StatusColors[it]!!)) }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
        Text(latest ?: "—", style = MaterialTheme.typography.titleMedium, color = tint, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ---- metric detail ---------------------------------------------------------

@Composable
private fun MetricDetailCard(
    key: String,
    entries: List<HealthEntry>,
    units: de.ledgerline.app.domain.model.HealthUnits,
    range: String,
    nowMs: Long,
    is12h: Boolean,
    weightGoalKg: Double?,
    onRange: (String) -> Unit,
    onEdit: (HealthEntry) -> Unit,
    onDelete: (HealthEntry) -> Unit,
) {
    val tint = Color(HealthMetrics.metric(key)?.tint ?: 0xFF6B7280)
    val inRange = HealthCompute.entriesInRange(entries, key, range, nowMs)
    val stats = HealthCompute.stats(entries, key, units)
    val unit = HealthCompute.unitLabel(key, units)

    Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(metricLabel(key), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RANGES.forEach { r ->
                FilterChip(selected = r == range, onClick = { onRange(r) }, label = { Text(rangeLabel(r)) })
            }
        }

        if (inRange.isEmpty()) {
            Column(Modifier.fillMaxWidth().height(120.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.health_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val series = buildChartSeries(key, inRange, units, tint)
            val bands = referenceBands(key, units)
            val goal = if (key == "weight" && weightGoalKg != null) HealthCompute.displaySingle("weight", weightGoalKg, units) else null
            HealthChart(series = series, bands = bands, goalLine = goal, goalColor = Brand.accent)
        }

        // Stats
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatCell(stringResource(R.string.health_latest), stats.latest)
            StatCell(stringResource(R.string.health_avg), stats.avg)
            StatCell(stringResource(R.string.health_min), stats.min)
            StatCell(stringResource(R.string.health_max), stats.max)
        }

        // Recent entries
        val recent = HealthCompute.entriesFor(entries, key).take(20)
        recent.forEach { e ->
            EntryRow(
                key = key,
                entry = e,
                display = HealthCompute.displayValue(key, e.v, e.v2, units),
                unit = unit,
                is12h = is12h,
                onEdit = { onEdit(e) },
                onDelete = { onDelete(e) },
            )
        }
    }
}

@Composable
private fun StatCell(label: String, value: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value ?: "—", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EntryRow(key: String, entry: HealthEntry, display: String, unit: String, is12h: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val status = HealthMetrics.classify(key, entry.v, entry.v2)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(StatusColors[status]!!))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("$display $unit", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            val meta = formatEntryTime(entry.ts, is12h) + if (entry.note.isNotBlank()) " · ${entry.note}" else ""
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.health_edit), modifier = Modifier.size(18.dp)) }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(18.dp)) }
    }
}

// ---- helpers shared with sub-composables -----------------------------------

@Composable
internal fun metricLabel(key: String): String = stringResource(
    when (key) {
        "weight" -> R.string.health_metric_weight
        "bp" -> R.string.health_metric_bp
        "pulse" -> R.string.health_metric_pulse
        "spo2" -> R.string.health_metric_spo2
        "temp" -> R.string.health_metric_temp
        "glucose" -> R.string.health_metric_glucose
        else -> R.string.dest_health
    },
)

@Composable
private fun rangeLabel(r: String): String = stringResource(
    when (r) {
        "7d" -> R.string.health_range_7d
        "30d" -> R.string.health_range_30d
        "90d" -> R.string.health_range_90d
        "1y" -> R.string.health_range_1y
        else -> R.string.health_range_all
    },
)

private fun buildChartSeries(key: String, inRange: List<HealthEntry>, units: de.ledgerline.app.domain.model.HealthUnits, tint: Color): List<ChartSeries> {
    val xs = inRange.map { Instant.parse(it.ts).toEpochMilli().toDouble() }
    val ys = inRange.mapIndexed { i, e -> xs[i] to HealthCompute.displaySingle(key, e.v, units) }
    return if (key == "bp") {
        val dia = inRange.mapIndexedNotNull { i, e -> e.v2?.let { xs[i] to it } }
        listOfNotNull(ChartSeries(ys, tint), dia.takeIf { it.isNotEmpty() }?.let { ChartSeries(it, tint.copy(alpha = 0.55f)) })
    } else {
        listOf(ChartSeries(ys, tint))
    }
}

private fun referenceBands(key: String, units: de.ledgerline.app.domain.model.HealthUnits): List<ChartBand> {
    fun t(c: Double) = if (units.temp == "f") HealthMetrics.cToF(c) else c
    return when (key) {
        "spo2" -> listOf(
            ChartBand(0.0, 92.0, Color(0x1AF43F5E)),
            ChartBand(92.0, 95.0, Color(0x1AE2915A)),
        )
        "pulse" -> listOf(ChartBand(60.0, 100.0, Color(0x1459AD6B)))
        "temp" -> listOf(
            ChartBand(t(38.0), t(39.0), Color(0x1AE2915A)),
            ChartBand(t(39.0), t(50.0), Color(0x1AF43F5E)),
        )
        "bp" -> listOf(
            ChartBand(0.0, 120.0, Color(0x1059AD6B)),
            ChartBand(120.0, 140.0, Color(0x14E2915A)),
            ChartBand(140.0, 300.0, Color(0x14F43F5E)),
        )
        else -> emptyList()
    }
}

internal fun formatEntryTime(iso: String, is12h: Boolean = false): String = try {
    val pattern = if (is12h) "d MMM yyyy, h:mm a" else "d MMM yyyy, HH:mm"
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))
} catch (_: Exception) { iso }

/** Share a metric's CSV via ACTION_SEND EXTRA_TEXT (no FileProvider needed, like the GPX export). */
private fun shareCsv(context: Context, csv: String, key: String) {
    val name = "health-$key.csv"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_TITLE, name)
        putExtra(Intent.EXTRA_SUBJECT, name)
        putExtra(Intent.EXTRA_TEXT, csv)
    }
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.health_export_csv))) }
}
