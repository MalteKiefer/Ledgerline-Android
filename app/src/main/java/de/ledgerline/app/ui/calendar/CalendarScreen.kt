package de.ledgerline.app.ui.calendar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.CalendarEvent
import de.ledgerline.app.domain.model.EventLocation
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.cardSurface
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private enum class CalView { MONTH, WEEK, DAY }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    vm: CalendarViewModel = hiltViewModel(),
) {
    var viewMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(CalView.MONTH) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    // Observed so the grid/day-list recompose when subscription overlays finish fetching.
    val subEvents by vm.subEvents.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<CalendarEvent?>(null) }
    var editorFor by remember { mutableStateOf<CalendarEvent?>(null) }
    var creating by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val text = runCatching { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() } }.getOrNull()
            if (!text.isNullOrBlank()) vm.importIcs(text)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        uri?.let { runCatching { context.contentResolver.openOutputStream(it)?.use { os -> os.write(vm.exportIcs().toByteArray()) } } }
    }

    if (creating || editorFor != null) {
        val target = editorFor
        CalendarEventEditor(
            initial = target,
            defaultDay = selectedDay,
            onSave = { id, title, desc, allDay, start, end, tz, loc, rrule, reminders ->
                vm.saveEvent(id, target?.calendarId ?: vm.defaultCalendarId(), title, desc, allDay, start, end, tz, loc, rrule, reminders)
            },
            onDelete = target?.let { e -> { vm.deleteEvent(e.id); editorFor = null; creating = false } },
            onBack = { editorFor = null; creating = false },
            modifier = modifier,
        )
        return
    }

    AppScaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResourceSafe(R.string.dest_calendar),
                onMenu = onMenu,
                actions = {
                    IconButton(onClick = { vm.goToday() }) {
                        Icon(Icons.Outlined.Today, stringResourceSafe(R.string.calendar_today))
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Outlined.Refresh, stringResourceSafe(R.string.action_refresh))
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, stringResourceSafe(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResourceSafe(R.string.calendar_import)) },
                            onClick = { menuOpen = false; importLauncher.launch(arrayOf("text/calendar", "text/*", "application/octet-stream")) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResourceSafe(R.string.calendar_export)) },
                            onClick = { menuOpen = false; exportLauncher.launch("ledgerline.ics") },
                        )
                    }
                },
            )
        },
    ) { padding ->
      androidx.compose.runtime.key(subEvents) {
      Box(Modifier.fillMaxSize().padding(padding)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            val today = LocalDate.now()
            val dm = remember { DateTimeFormatter.ofPattern("d. MMM", Locale.getDefault()) }
            val dayHeaderFmt = remember { DateTimeFormatter.ofPattern("EEE, d. MMM", Locale.getDefault()) }
            val weekStart = DayOfWeek.MONDAY

            // Adaptive header + prev/next (steps by month / week / day).
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    when (viewMode) {
                        CalView.MONTH -> vm.prevMonth()
                        CalView.WEEK -> vm.selectDay(selectedDay.minusWeeks(1))
                        CalView.DAY -> vm.selectDay(selectedDay.minusDays(1))
                    }
                }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResourceSafe(R.string.calendar_prev)) }
                Text(
                    when (viewMode) {
                        CalView.MONTH -> month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year
                        CalView.WEEK -> { val ws = selectedDay.with(weekStart); "${ws.format(dm)} – ${ws.plusDays(6).format(dm)}" }
                        CalView.DAY -> selectedDay.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
                    },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    when (viewMode) {
                        CalView.MONTH -> vm.nextMonth()
                        CalView.WEEK -> vm.selectDay(selectedDay.plusWeeks(1))
                        CalView.DAY -> vm.selectDay(selectedDay.plusDays(1))
                    }
                }) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, stringResourceSafe(R.string.calendar_next)) }
            }

            // View switcher.
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                listOf(CalView.MONTH to R.string.view_month, CalView.WEEK to R.string.view_week, CalView.DAY to R.string.view_day).forEach { (mode, res) ->
                    FilterChip(selected = viewMode == mode, onClick = { viewMode = mode }, label = { Text(stringResourceSafe(res)) })
                }
            }

            @Composable
            fun DayEventList(day: LocalDate) {
                val evs = vm.eventsForDay(day)
                if (evs.isEmpty()) {
                    Text(stringResourceSafe(R.string.calendar_no_events), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        evs.forEach { e -> EventRow(e, parseHex(vm.colorFor(e.calendarId))) { detail = e } }
                    }
                }
            }

            when (viewMode) {
                CalView.MONTH -> {
                    Row(Modifier.fillMaxWidth()) {
                        for (i in 0..6) {
                            Text(
                                weekStart.plus(i.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    val first = month.atDay(1)
                    val lead = ((first.dayOfWeek.value - weekStart.value) + 7) % 7
                    val gridStart = first.minusDays(lead.toLong())
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        for (w in 0 until 6) {
                            Row(Modifier.fillMaxWidth()) {
                                for (d in 0 until 7) {
                                    val date = gridStart.plusDays((w * 7 + d).toLong())
                                    DayCell(
                                        date = date, inMonth = date.month == month.month, isToday = date == today,
                                        isSelected = date == selectedDay,
                                        dotColors = vm.eventsForDay(date).take(3).map { parseHex(vm.colorFor(it.calendarId)) },
                                        onClick = { vm.selectDay(date) }, modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                    Text(
                        selectedDay.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                    if (ui.loading && ui.events.isEmpty()) {
                        Text(stringResourceSafe(R.string.calendar_loading), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                    } else DayEventList(selectedDay)
                }
                CalView.WEEK -> {
                    val ws = selectedDay.with(weekStart)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                        for (i in 0..6) {
                            val d = ws.plusDays(i.toLong())
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    d.format(dayHeaderFmt),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (d == today) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.clickable { vm.selectDay(d); viewMode = CalView.DAY },
                                )
                                DayEventList(d)
                            }
                        }
                    }
                }
                CalView.DAY -> {
                    Spacer(Modifier.size(8.dp))
                    DayEventList(selectedDay)
                }
            }
            Spacer(Modifier.size(24.dp))
        }
        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
        ) { Icon(Icons.Outlined.Add, stringResourceSafe(R.string.calendar_add)) }
      }
      }
    }

    detail?.let { e ->
        EventDetailSheet(
            e = e,
            colorHex = vm.colorFor(e.calendarId),
            calendarName = vm.calendarName(e.calendarId),
            canEdit = !vm.isFeed(e.calendarId),
            onEdit = { detail = null; editorFor = e },
            onDismiss = { detail = null },
        )
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    dotColors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .then(
                if (isSelected) Modifier.background(Brand.accentGradient, RoundedCornerShape(12.dp))
                else if (isToday) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                else Modifier,
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isSelected -> Color.White
                    !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp)) {
                dotColors.forEach { c ->
                    Box(Modifier.size(5.dp).background(if (isSelected) Color.White else c, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun EventRow(e: CalendarEvent, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .cardSurface()
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(e.title.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            val time = eventTimeLabel(e)
            if (time.isNotBlank()) {
                Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (e.rrule.isNotBlank()) {
            Icon(Icons.Outlined.Repeat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EventDetailSheet(
    e: CalendarEvent,
    colorHex: String,
    calendarName: String,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val color = parseHex(colorHex)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(44.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Event, null, tint = color)
                }
                Column(Modifier.weight(1f)) {
                    Text(e.title.ifBlank { "—" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (calendarName.isNotBlank()) {
                        Text(calendarName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            eventWhenLabel(e).takeIf { it.isNotBlank() }?.let {
                DetailLine(Icons.Outlined.Schedule, it)
            }
            e.location?.takeIf { it.label.isNotBlank() }?.let { loc ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { launchNavigation(context, loc) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.LocationOn, null, tint = color)
                    Text(loc.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(Icons.Outlined.Directions, stringResourceSafe(R.string.calendar_navigate), tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (e.description.isNotBlank()) {
                DetailLine(Icons.Outlined.Notes, e.description)
            }
            if (canEdit) {
                de.ledgerline.app.ui.theme.PrimaryGradientButton(
                    text = stringResourceSafe(R.string.action_edit),
                    onClick = onEdit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DetailLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, modifier = Modifier.size(20.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Open the device's map/navigation app for an event location (coords if known, else a name search). */
private fun launchNavigation(context: android.content.Context, loc: EventLocation) {
    val q = if (loc.lat != null && loc.lng != null) "${loc.lat},${loc.lng}(${android.net.Uri.encode(loc.label)})"
    else android.net.Uri.encode(loc.label)
    val geo = android.net.Uri.parse("geo:0,0?q=$q")
    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, geo)) }
        .onFailure {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.openstreetmap.org/search?query=${android.net.Uri.encode(loc.label)}")),
                )
            }
        }
}

/** Full "when" label: date for all-day, else date + time range. */
private fun eventWhenLabel(e: CalendarEvent): String {
    val d = CalendarViewModel.dateOf(e.start) ?: return ""
    val dateStr = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
    if (e.allDay || !e.start.contains('T')) return dateStr
    val t = eventTimeLabel(e)
    return if (t.isBlank()) dateStr else "$dateStr · $t"
}

// ---- helpers ----

@Composable
private fun stringResourceSafe(id: Int): String = androidx.compose.ui.res.stringResource(id)

private fun parseHex(hex: String): Color = try {
    val h = hex.removePrefix("#")
    val v = h.toLong(16)
    when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> Color(0xFF7066F5)
    }
} catch (_: Exception) {
    Color(0xFF7066F5)
}

/** "09:00 – 10:30", "Ganztägig", or a start time; date-only spans show as all-day. */
private fun eventTimeLabel(e: CalendarEvent): String {
    if (e.allDay || e.start.length <= 10) return ""
    val s = timeOf(e.start)
    val en = e.end.takeIf { it.isNotBlank() && it.length > 10 }?.let { timeOf(it) }
    return if (s.isBlank()) "" else if (en != null && en.isNotBlank()) "$s – $en" else s
}

private fun timeOf(v: String): String = try {
    if (v.endsWith("Z") || v.contains('+')) {
        OffsetDateTime.parse(v).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    } else {
        java.time.LocalDateTime.parse(v).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    }
} catch (_: Exception) {
    ""
}
