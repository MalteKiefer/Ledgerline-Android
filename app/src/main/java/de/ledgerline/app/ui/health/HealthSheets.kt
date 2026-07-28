package de.ledgerline.app.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.core.health.HealthFasting
import de.ledgerline.app.core.health.HealthMetrics
import de.ledgerline.app.domain.model.HealthEntry
import de.ledgerline.app.domain.model.HealthFast
import de.ledgerline.app.domain.model.HealthProfile
import de.ledgerline.app.domain.model.HealthUnits
import de.ledgerline.app.ui.theme.PrimaryGradientButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ---- Measurement editor ----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeasurementEditorSheet(
    initialMetric: String,
    editing: HealthEntry?,
    units: HealthUnits,
    onDismiss: () -> Unit,
    onSave: (metric: String, v: String, v2: String, tsIso: String, note: String) -> Boolean,
) {
    var metric by remember { mutableStateOf(initialMetric) }
    var value by remember { mutableStateOf(editing?.let { displayForEdit(it, units) } ?: "") }
    var value2 by remember { mutableStateOf(editing?.v2?.let { fmtNum(it) } ?: "") }
    var note by remember { mutableStateOf(editing?.note ?: "") }
    var millis by remember { mutableStateOf(editing?.let { parseMsOrNow(it.ts) } ?: System.currentTimeMillis()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(if (editing != null) R.string.health_edit else R.string.health_add),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )

            if (editing == null) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HealthMetrics.METRICS.forEach { m ->
                        FilterChip(selected = m.key == metric, onClick = { metric = m.key }, label = { Text(metricLabel(m.key)) })
                    }
                }
            }

            val unitLabel = de.ledgerline.app.core.health.HealthCompute.unitLabel(metric, units)
            if (metric == "bp") {
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.health_systolic)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = value2, onValueChange = { value2 = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.health_diastolic)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            } else {
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("${stringResource(R.string.health_value)} ($unitLabel)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            DateTimeField(stringResource(R.string.health_when), millis) { millis = it }

            OutlinedTextField(
                value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.health_note)) },
            )

            PrimaryGradientButton(stringResource(R.string.action_save), onClick = {
                if (onSave(metric, value, value2, Instant.ofEpochMilli(millis).toString(), note.trim())) onDismiss()
            })
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ---- Master data -----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MasterDataSheet(
    profile: HealthProfile,
    units: HealthUnits,
    onDismiss: () -> Unit,
    onSave: (HealthProfile, HealthUnits) -> Unit,
) {
    var birthdate by remember { mutableStateOf(profile.birthdate) }
    var height by remember { mutableStateOf(profile.heightCm?.let { fmtNum(it) } ?: "") }
    var sex by remember { mutableStateOf(profile.sex) }
    var goal by remember { mutableStateOf(profile.weightGoalKg?.let { fmtNum(it) } ?: "") }
    var uWeight by remember { mutableStateOf(units.weight) }
    var uTemp by remember { mutableStateOf(units.temp) }
    var uGlucose by remember { mutableStateOf(units.glucose) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.health_master), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            DateField(stringResource(R.string.health_birthdate), birthdate) { birthdate = it }
            OutlinedTextField(
                value = height, onValueChange = { height = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.health_height)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            Text(stringResource(R.string.health_sex), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sexChip(R.string.health_sex_m, "m", sex) { sex = it }
                sexChip(R.string.health_sex_f, "f", sex) { sex = it }
                sexChip(R.string.health_sex_x, "x", sex) { sex = it }
                sexChip(R.string.health_sex_unset, "", sex) { sex = it }
            }

            OutlinedTextField(
                value = goal, onValueChange = { goal = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.health_weight_goal)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            Text(stringResource(R.string.health_units), style = MaterialTheme.typography.labelLarge)
            UnitToggle(stringResource(R.string.health_unit_weight), listOf("kg" to "kg", "lb" to "lb"), uWeight) { uWeight = it }
            UnitToggle(stringResource(R.string.health_unit_temp), listOf("c" to "°C", "f" to "°F"), uTemp) { uTemp = it }
            UnitToggle(stringResource(R.string.health_unit_glucose), listOf("mgdl" to "mg/dL", "mmoll" to "mmol/L"), uGlucose) { uGlucose = it }

            PrimaryGradientButton(stringResource(R.string.action_save), onClick = {
                onSave(
                    profile.copy(
                        birthdate = birthdate.trim(),
                        heightCm = height.trim().replace(',', '.').toDoubleOrNull(),
                        sex = sex,
                        weightGoalKg = goal.trim().replace(',', '.').toDoubleOrNull(),
                    ),
                    HealthUnits(weight = uWeight, glucose = uGlucose, temp = uTemp),
                )
            })
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun sexChip(labelRes: Int, value: String, current: String, onSelect: (String) -> Unit) {
    FilterChip(selected = current == value, onClick = { onSelect(value) }, label = { Text(stringResource(labelRes)) })
}

@Composable
private fun UnitToggle(label: String, options: List<Pair<String, String>>, current: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        options.forEach { (v, disp) ->
            FilterChip(selected = current == v, onClick = { onSelect(v) }, label = { Text(disp) })
        }
    }
}

// ---- Fast editor -----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FastEditorSheet(
    fast: HealthFast,
    onDismiss: () -> Unit,
    onSave: (startIso: String, endIso: String?, target: Int, note: String) -> Unit,
) {
    var startMs by remember { mutableStateOf(parseMsOrNow(fast.start)) }
    var running by remember { mutableStateOf(fast.end.isNullOrEmpty()) }
    var endMs by remember { mutableStateOf(fast.end?.let { parseMsOrNow(it) } ?: System.currentTimeMillis()) }
    var target by remember { mutableStateOf((fast.targetHours ?: 16).toString()) }
    var note by remember { mutableStateOf(fast.note) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.health_fast_edit), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            DateTimeField(stringResource(R.string.health_fast_start_time), startMs) { startMs = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = running, onClick = { running = true }, label = { Text(stringResource(R.string.health_fast_end_running)) })
                FilterChip(selected = !running, onClick = { running = false }, label = { Text(stringResource(R.string.health_fast_end_time)) })
            }
            if (!running) DateTimeField(stringResource(R.string.health_fast_end_time), endMs) { endMs = it }
            OutlinedTextField(
                value = target, onValueChange = { target = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.health_fast_target)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.health_note)) })
            PrimaryGradientButton(stringResource(R.string.action_save), onClick = {
                onSave(
                    Instant.ofEpochMilli(startMs).toString(),
                    if (running) null else Instant.ofEpochMilli(endMs).toString(),
                    target.trim().toIntOrNull() ?: 16,
                    note.trim(),
                )
            })
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ---- Confirm dialog --------------------------------------------------------

@Composable
internal fun ConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ---- Date / time fields ----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeField(label: String, millis: Long, onChange: (Long) -> Unit) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val fmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm") }
    val text = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(fmt)

    OutlinedTextField(
        value = text, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        trailingIcon = { TextButton(onClick = { showDate = true }) { Text("…") } },
    )

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = millis)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        // Keep the current time-of-day, replace the date.
                        val old = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                        val date = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        val merged = date.atTime(old.hour, old.minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        onChange(merged)
                    }
                    showDate = false; showTime = true
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }

    if (showTime) {
        val zdt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val timeState = rememberTimePickerState(initialHour = zdt.hour, initialMinute = zdt.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    val merged = zdt.toLocalDate().atTime(timeState.hour, timeState.minute)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    onChange(merged); showTime = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state = timeState) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, isoDate: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = isoDate, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        trailingIcon = { TextButton(onClick = { show = true }) { Text("…") } },
    )
    if (show) {
        val initial = try { LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() } catch (_: Exception) { null }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(d.toString())
                    }
                    show = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

// ---- small helpers ---------------------------------------------------------

private fun parseMsOrNow(iso: String): Long = try { Instant.parse(iso).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }

private fun fmtNum(d: Double): String = de.ledgerline.app.core.health.HealthCompute.fmt(d)

/** The display-unit value string for editing an existing entry (single metrics only; bp uses v2). */
private fun displayForEdit(e: HealthEntry, units: HealthUnits): String =
    fmtNum(de.ledgerline.app.core.health.HealthCompute.displaySingle(e.metric, e.v, units))
