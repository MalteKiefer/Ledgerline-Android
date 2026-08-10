package de.ledgerline.app.ui.todos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.calendar.Calendar
import de.ledgerline.app.domain.model.calendar.CalendarTodo
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.RefreshBox
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.money.FilledField
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.cardSurface
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The "Aufgaben" (VTODO task-list) tab: task lists, tasks with a completion toggle, and CRUD. */
@Composable
fun TodosSection(modifier: Modifier = Modifier, vm: TodosViewModel = hiltViewModel()) {
    val defaultName = stringResource(R.string.todos_default_list)
    LaunchedEffect(Unit) { vm.bootstrap(defaultName) }

    val lists by vm.lists.collectAsStateWithLifecycle()
    val todos by vm.todos.collectAsStateWithLifecycle()
    val selectedList by vm.selectedList.collectAsStateWithLifecycle()
    val showDone by vm.showDone.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var addingList by remember { mutableStateOf(false) }

    if (creating || editing != null) {
        TodoEditScreen(
            vm = vm,
            todoId = editing,
            lists = lists,
            defaultCalendar = selectedList ?: lists.firstOrNull()?.id,
            onBack = { creating = false; editing = null },
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.tab_todos))

        // Task-list picker.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selectedList == null, { vm.selectList(null) }, label = { Text(stringResource(R.string.todos_all_lists)) })
            lists.forEach { l -> FilterChip(selectedList == l.id, { vm.selectList(l.id) }, label = { Text(l.name) }) }
            AssistChip(onClick = { addingList = true }, label = { Text(stringResource(R.string.todos_add_list)) }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
        }
        // Open / all filter.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!showDone, { if (showDone) vm.toggleShowDone() }, label = { Text(stringResource(R.string.todos_open)) })
            FilterChip(showDone, { if (!showDone) vm.toggleShowDone() }, label = { Text(stringResource(R.string.todos_all)) })
        }

        val shown = todos.filter { (selectedList == null || it.calendar == selectedList) && (showDone || !it.done) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RefreshBox(refreshing = refreshing, onRefresh = { vm.refresh() }) {
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.todos_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(48.dp))
                    }
                } else LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { t ->
                        TodoRow(
                            todo = t,
                            listName = vm.listName(t.calendar).takeIf { selectedList == null },
                            onToggle = { vm.setDone(t, !t.done) },
                            onClick = { editing = t.id },
                        )
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(stringResource(R.string.todos_new)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (addingList) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingList = false },
            title = { Text(stringResource(R.string.todos_add_list)) },
            text = { FilledField(name, { name = it }, R.string.todos_list) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.addList(name) {}; addingList = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { addingList = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun TodoRow(todo: CalendarTodo, listName: String?, onToggle: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().cardSurface(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onToggle) {
            if (todo.done) Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Brand.tintGreen)
            else Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(
            Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                todo.summary.orEmpty().ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val sub = listOfNotNull(todo.due?.let { fmtDate(it) }, priorityLabel(todo.priority), listName).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PriorityDot(todo.priority)
    }
}

@Composable
private fun PriorityDot(priority: Int?) {
    val p = priority ?: 0
    if (p == 0) return
    val color = when {
        p in 1..4 -> MaterialTheme.colorScheme.error
        p == 5 -> Brand.tintOrange
        else -> Brand.tintBlue
    }
    Box(Modifier.padding(end = 12.dp).size(10.dp).clip(CircleShape).background(color))
}

// ---------------------------------------------------------------------------
//  Editor
// ---------------------------------------------------------------------------
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TodoEditScreen(
    vm: TodosViewModel,
    todoId: String?,
    lists: List<Calendar>,
    defaultCalendar: String?,
    onBack: () -> Unit,
) {
    val existing = todoId?.let { vm.todo(it) }
    var summary by remember { mutableStateOf(existing?.summary ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var calendarId by remember { mutableStateOf(existing?.calendar ?: defaultCalendar ?: lists.firstOrNull()?.id ?: "") }
    var dueMillis by remember { mutableStateOf(existing?.due?.let { parseMillis(it) }) }
    var allDay by remember { mutableStateOf(existing?.allDay ?: false) }
    var priority by remember { mutableStateOf(existing?.priority ?: 0) }
    var busy by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    AppScaffold(topBar = {
        AppTopBar(
            title = stringResource(if (todoId == null) R.string.todos_new else R.string.action_edit),
            onBack = onBack,
            actions = {
                TextButton(enabled = !busy && summary.isNotBlank() && calendarId.isNotBlank(), onClick = {
                    busy = true
                    val due = dueMillis?.let { toIsoInstant(it) }
                    vm.save(todoId, calendarId, summary, description, due, allDay, priority.takeIf { it != 0 }, existing?.etag) { ok ->
                        busy = false; if (ok) onBack()
                    }
                }) { Text(stringResource(R.string.action_save)) }
            },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FilledField(summary, { summary = it }, R.string.todos_summary)
            FilledField(description, { description = it }, R.string.todos_description, singleLine = false)

            if (lists.size > 1) {
                SectionLabel(stringResource(R.string.todos_list))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lists.forEach { l -> FilterChip(calendarId == l.id, { calendarId = l.id }, label = { Text(l.name) }) }
                }
            }

            SectionLabel(stringResource(R.string.todos_due))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showPicker = true }) { Text(dueMillis?.let { fmtMillis(it) } ?: stringResource(R.string.todos_due)) }
                if (dueMillis != null) TextButton(onClick = { dueMillis = null }) { Text(stringResource(R.string.action_delete)) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.todos_all_day), Modifier.weight(1f))
                Switch(checked = allDay, onCheckedChange = { allDay = it })
            }

            SectionLabel(stringResource(R.string.todos_priority))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrioChip(0, priority) { priority = 0 }
                PrioChip(1, priority) { priority = 1 }
                PrioChip(5, priority) { priority = 5 }
                PrioChip(9, priority) { priority = 9 }
            }

            if (todoId != null) {
                TextButton(onClick = { vm.delete(todoId) { onBack() } }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = dueMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = { dueMillis = state.selectedDateMillis; showPicker = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun PrioChip(value: Int, selected: Int, onClick: () -> Unit) {
    FilterChip(selected == value, onClick, label = { Text(priorityChipLabel(value)) })
}

@Composable
private fun priorityChipLabel(p: Int): String = stringResource(
    when {
        p == 0 -> R.string.todos_prio_none
        p in 1..4 -> R.string.todos_prio_high
        p == 5 -> R.string.todos_prio_medium
        else -> R.string.todos_prio_low
    },
)

@Composable
private fun priorityLabel(p: Int?): String? {
    val v = p ?: 0
    if (v == 0) return null
    return priorityChipLabel(v)
}

// ---- date helpers ----
private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC)

private fun fmtMillis(millis: Long): String = dateFmt.format(Instant.ofEpochMilli(millis))
private fun fmtDate(iso: String): String = runCatching { dateFmt.format(Instant.parse(iso)) }.getOrElse { iso.take(10) }
private fun parseMillis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
private fun toIsoInstant(millis: Long): String = Instant.ofEpochMilli(millis).toString()
