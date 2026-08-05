package de.ledgerline.app.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.CalendarEvent
import de.ledgerline.app.domain.model.EventLocation
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.PrimaryGradientButton
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Create/edit a single calendar event. Times are stored as local wall-clock ISO
 * (`yyyy-MM-ddTHH:mm`) with a tz hint; all-day uses date-only (`yyyy-MM-dd`) — matching the
 * web `CalendarEvent` contract. Recurrence / reminders ride along untouched via the record's raw.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CalendarEventEditor(
    initial: CalendarEvent?,
    defaultDay: LocalDate,
    onSave: (id: String?, title: String, description: String, allDay: Boolean, start: String, end: String, tz: String, location: EventLocation?, rrule: String) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var locationLabel by remember { mutableStateOf(initial?.location?.label ?: "") }
    var allDay by remember { mutableStateOf(initial?.allDay ?: false) }

    val initStartDate = initial?.start?.let { CalendarViewModel.dateOf(it) } ?: defaultDay
    val initEndDate = initial?.end?.let { CalendarViewModel.dateOf(it) } ?: initStartDate
    var startDate by remember { mutableStateOf(initStartDate) }
    var endDate by remember { mutableStateOf(initEndDate) }
    var startTime by remember { mutableStateOf(parseTime(initial?.start) ?: LocalTime.of(9, 0)) }
    var endTime by remember { mutableStateOf(parseTime(initial?.end) ?: LocalTime.of(10, 0)) }

    // Recurrence editor state (parsed from the event's RRULE).
    val initRule = remember { de.ledgerline.app.core.calendar.CalendarRecurrence.parse(initial?.rrule ?: "") }
    var freq by remember { mutableStateOf(initRule?.freq ?: "none") }
    var interval by remember { mutableStateOf((initRule?.interval ?: 1).coerceAtLeast(1)) }
    val byday = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { initRule?.byday?.let { addAll(it) } } }
    var ends by remember { mutableStateOf(when { initRule?.count != null -> "count"; initRule?.until != null -> "until"; else -> "never" }) }
    var count by remember { mutableStateOf(initRule?.count ?: 10) }
    var until by remember { mutableStateOf(initRule?.until ?: startDate.plusMonths(3)) }
    var pickUntil by remember { mutableStateOf(false) }

    // date/time picker targets: null = closed; else which field is being edited.
    var pickDate by remember { mutableStateOf<String?>(null) }   // "start" | "end"
    var pickTime by remember { mutableStateOf<String?>(null) }

    AppScaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(if (initial == null) R.string.calendar_new_event else R.string.calendar_edit_event),
                onBack = onBack,
                actions = {
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text(stringResource(R.string.calendar_field_title)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.calendar_all_day), Modifier.weight(1f))
                Switch(checked = allDay, onCheckedChange = { allDay = it })
            }

            // Start
            Text(stringResource(R.string.calendar_start), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldChip(startDate.format(dateFmt), Modifier.weight(1f)) { pickDate = "start" }
                if (!allDay) FieldChip(startTime.format(timeFmt), Modifier.weight(1f)) { pickTime = "start" }
            }
            // End
            Text(stringResource(R.string.calendar_end), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldChip(endDate.format(dateFmt), Modifier.weight(1f)) { pickDate = "end" }
                if (!allDay) FieldChip(endTime.format(timeFmt), Modifier.weight(1f)) { pickTime = "end" }
            }

            OutlinedTextField(
                value = locationLabel, onValueChange = { locationLabel = it },
                label = { Text(stringResource(R.string.calendar_field_location)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text(stringResource(R.string.calendar_field_notes)) },
                minLines = 3, modifier = Modifier.fillMaxWidth(),
            )

            // ---- Recurrence ----
            Text(stringResource(R.string.calendar_repeat), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            val freqLabels = listOf(
                "none" to stringResource(R.string.repeat_none),
                "DAILY" to stringResource(R.string.repeat_daily),
                "WEEKLY" to stringResource(R.string.repeat_weekly),
                "MONTHLY" to stringResource(R.string.repeat_monthly),
                "YEARLY" to stringResource(R.string.repeat_yearly),
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                freqLabels.forEach { (key, label) ->
                    androidx.compose.material3.FilterChip(selected = freq == key, onClick = { freq = key }, label = { Text(label) })
                }
            }
            if (freq != "none") {
                OutlinedTextField(
                    value = interval.toString(),
                    onValueChange = { interval = it.filter { c -> c.isDigit() }.toIntOrNull()?.coerceIn(1, 999) ?: 1 },
                    label = { Text(stringResource(R.string.calendar_interval)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (freq == "WEEKLY") {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        de.ledgerline.app.core.calendar.CalendarRecurrence.WEEKDAYS.forEach { wd ->
                            androidx.compose.material3.FilterChip(
                                selected = byday.contains(wd),
                                onClick = { if (byday.contains(wd)) byday.remove(wd) else byday.add(wd) },
                                label = { Text(wd) },
                            )
                        }
                    }
                }
                Text(stringResource(R.string.calendar_ends), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "never" to stringResource(R.string.ends_never),
                        "count" to stringResource(R.string.ends_count),
                        "until" to stringResource(R.string.ends_until),
                    ).forEach { (key, label) ->
                        androidx.compose.material3.FilterChip(selected = ends == key, onClick = { ends = key }, label = { Text(label) })
                    }
                }
                if (ends == "count") {
                    OutlinedTextField(
                        value = count.toString(),
                        onValueChange = { count = it.filter { c -> c.isDigit() }.toIntOrNull()?.coerceIn(1, 999) ?: 1 },
                        label = { Text(stringResource(R.string.calendar_count)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (ends == "until") {
                    FieldChip(until.format(dateFmt), Modifier.fillMaxWidth()) { pickUntil = true }
                }
            }

            PrimaryGradientButton(
                text = stringResource(R.string.action_save),
                onClick = {
                    val tz = ZoneId.systemDefault().id
                    val start: String
                    val end: String
                    if (allDay) {
                        start = startDate.toString()
                        end = endDate.toString()
                    } else {
                        start = startDate.toString() + "T" + startTime.format(timeFmt)
                        end = endDate.toString() + "T" + endTime.format(timeFmt)
                    }
                    val rrule = de.ledgerline.app.core.calendar.CalendarRecurrence.build(
                        freq, interval, byday.toList(), ends, count, until.toString(),
                    )
                    onSave(initial?.id, title.trim(), description.trim(), allDay, start, end, tz,
                        locationLabel.trim().takeIf { it.isNotBlank() }?.let { EventLocation(label = it) }, rrule)
                    onBack()
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (pickDate != null) {
        val target = pickDate!!
        val cur = if (target == "start") startDate else endDate
        val state = rememberDatePickerState(initialSelectedDateMillis = cur.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        if (target == "start") { startDate = d; if (endDate.isBefore(d)) endDate = d } else endDate = d
                    }
                    pickDate = null
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = null }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }

    if (pickTime != null) {
        val target = pickTime!!
        val cur = if (target == "start") startTime else endTime
        val ts = rememberTimePickerState(initialHour = cur.hour, initialMinute = cur.minute, is24Hour = true)
        DatePickerDialog(
            onDismissRequest = { pickTime = null },
            confirmButton = {
                TextButton(onClick = {
                    val t = LocalTime.of(ts.hour, ts.minute)
                    if (target == "start") startTime = t else endTime = t
                    pickTime = null
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = null }) { Text(stringResource(R.string.action_cancel)) } },
        ) { Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { TimePicker(state = ts) } }
    }

    if (pickUntil) {
        val state = rememberDatePickerState(initialSelectedDateMillis = until.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickUntil = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { until = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickUntil = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickUntil = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun FieldChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedTextField(
        value = text, onValueChange = {}, readOnly = true, enabled = false,
        modifier = modifier.clickable { onClick() },
        singleLine = true,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            disabledBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun parseTime(v: String?): LocalTime? {
    if (v.isNullOrBlank() || v.length <= 10 || !v.contains('T')) return null
    return try {
        val t = v.substringAfter('T').take(5)
        LocalTime.parse(t)
    } catch (_: Exception) {
        null
    }
}
