package de.ledgerline.app.ui.calendar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.core.calendar.Holidays
import de.ledgerline.app.ui.theme.cardSurface

private val CAL_PALETTE = listOf("#7066f5", "#9e70fa", "#3b9fd6", "#59ad6b", "#e2915a", "#3fae9f", "#d1607e", "#6b7280")

/**
 * Calendar settings (reached from the app Settings): reminder notifications, birthday/holiday
 * feeds, public .ics subscriptions, and calendar management. Backed by [CalendarViewModel] so
 * edits persist to the same sealed store.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarSettingsContent(padding: PaddingValues, vm: CalendarViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val notify by vm.notificationsEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- Notifications ----
        SettingsCard(stringResource(R.string.calendar_notifications)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.calendar_notifications_sub), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(
                    checked = notify,
                    onCheckedChange = { on ->
                        vm.setNotificationsEnabled(on)
                        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
        }

        // ---- Feeds ----
        SettingsCard(stringResource(R.string.calendar_feeds_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.calendar_feed_birthdays), Modifier.weight(1f))
                Switch(checked = ui.birthdaysOn, onCheckedChange = { on -> vm.saveSettings(on, ui.holidayCountries) })
            }
            Text(stringResource(R.string.calendar_feed_holidays), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Holidays.COUNTRIES.forEach { c ->
                    FilterChip(
                        selected = ui.holidayCountries.contains(c),
                        onClick = {
                            val cur = ui.holidayCountries
                            vm.saveSettings(ui.birthdaysOn, if (cur.contains(c)) cur - c else cur + c)
                        },
                        label = { Text(c) },
                    )
                }
            }
        }

        // ---- Subscriptions ----
        SettingsCard(stringResource(R.string.calendar_subscriptions)) {
            ui.subscriptions.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name.ifBlank { s.url }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    IconButton(onClick = { vm.removeSubscription(s.id) }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_delete)) }
                }
            }
            var subName by remember { mutableStateOf("") }
            var subUrl by remember { mutableStateOf("") }
            OutlinedTextField(subName, { subName = it }, label = { Text(stringResource(R.string.calendar_field_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(subUrl, { subUrl = it }, label = { Text("https:// / webcal://") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(
                onClick = { if (subUrl.isNotBlank()) { vm.addSubscription(subName.trim(), subUrl.trim()); subName = ""; subUrl = "" } },
                enabled = subUrl.isNotBlank(),
            ) { Text(stringResource(R.string.calendar_add_subscription)) }
        }

        // ---- Calendars ----
        SettingsCard(stringResource(R.string.calendar_manage)) {
            ui.calendars.forEach { c ->
                key(c.id) {
                    var name by remember { mutableStateOf(c.name) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.weight(1f),
                                trailingIcon = {
                                    if (name.isNotBlank() && name != c.name) {
                                        IconButton(onClick = { vm.renameCalendar(c.id, name.trim()) }) { Icon(Icons.Outlined.Check, stringResource(R.string.action_save)) }
                                    }
                                },
                            )
                            IconButton(onClick = { vm.setDefaultCalendar(c.id) }) {
                                Icon(if (c.isDefault) Icons.Outlined.Star else Icons.Outlined.StarBorder, stringResource(R.string.calendar_set_default))
                            }
                            IconButton(onClick = { vm.deleteCalendar(c.id) }, enabled = ui.calendars.size > 1) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.action_delete))
                            }
                        }
                        ColorPalette(selected = c.color) { vm.setCalendarColor(c.id, it) }
                    }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.calendar_add_calendar), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            var newName by remember { mutableStateOf("") }
            var newColor by remember { mutableStateOf(CAL_PALETTE.first()) }
            OutlinedTextField(newName, { newName = it }, label = { Text(stringResource(R.string.calendar_field_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ColorPalette(selected = newColor) { newColor = it }
            TextButton(
                onClick = { if (newName.isNotBlank()) { vm.addCalendar(newName.trim(), newColor); newName = "" } },
                enabled = newName.isNotBlank(),
            ) { Text(stringResource(R.string.calendar_add_calendar)) }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().cardSurface().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPalette(selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CAL_PALETTE.forEach { hex ->
            val sel = hex.equals(selected, ignoreCase = true)
            Box(
                Modifier.size(26.dp).background(hexColor(hex), CircleShape)
                    .then(if (sel) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable { onPick(hex) },
            )
        }
    }
}

private fun hexColor(hex: String): Color = try {
    val h = hex.removePrefix("#"); val v = h.toLong(16)
    when (h.length) { 6 -> Color(0xFF000000 or v); 8 -> Color(v); else -> Color(0xFF7066F5) }
} catch (_: Exception) { Color(0xFF7066F5) }
