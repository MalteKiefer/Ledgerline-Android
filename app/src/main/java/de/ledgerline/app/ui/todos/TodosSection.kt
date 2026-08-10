package de.ledgerline.app.ui.todos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.calendar.Calendar
import de.ledgerline.app.domain.model.calendar.CalendarTodo
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.RefreshBox
import de.ledgerline.app.ui.money.FilledField
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.cardSurface
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** The "Aufgaben" (VTODO task-list) tab: task lists, tasks with a completion toggle, quick-add editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodosSection(modifier: Modifier = Modifier, vm: TodosViewModel = hiltViewModel()) {
    val defaultName = stringResource(R.string.todos_default_list)
    LaunchedEffect(Unit) { vm.bootstrap(defaultName) }

    val lists by vm.lists.collectAsStateWithLifecycle()
    val todos by vm.todos.collectAsStateWithLifecycle()
    val selectedList by vm.selectedList.collectAsStateWithLifecycle()
    val showDone by vm.showDone.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    // null = closed; "" = create; other = edit that todo id.
    var editorTarget by remember { mutableStateOf<String?>(null) }
    var addingList by remember { mutableStateOf(false) }

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
                            onClick = { editorTarget = t.id },
                        )
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { editorTarget = "" },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(stringResource(R.string.todos_new)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (editorTarget != null) {
        TodoEditorSheet(
            vm = vm,
            todoId = editorTarget!!.ifBlank { null },
            lists = lists,
            defaultCalendar = selectedList ?: lists.firstOrNull()?.id,
            onDismiss = { editorTarget = null },
        )
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
    Box(Modifier.padding(end = 12.dp).size(10.dp).clip(CircleShape).background(priorityColor(p)))
}

// ---------------------------------------------------------------------------
//  Quick-add editor — a modern task-app bottom sheet: title-first (autofocus +
//  keyboard), inline metadata chips (due with quick presets / priority / list),
//  a collapsible note, and a round send button.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoEditorSheet(
    vm: TodosViewModel,
    todoId: String?,
    lists: List<Calendar>,
    defaultCalendar: String?,
    onDismiss: () -> Unit,
) {
    val existing = todoId?.let { vm.todo(it) }
    var summary by remember { mutableStateOf(existing?.summary ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var calendarId by remember { mutableStateOf(existing?.calendar ?: defaultCalendar ?: lists.firstOrNull()?.id ?: "") }
    var dueMillis by remember { mutableStateOf(existing?.due?.let { parseMillis(it) }) }
    var allDay by remember { mutableStateOf(existing?.allDay ?: false) }
    var priority by remember { mutableStateOf(existing?.priority ?: 0) }
    var busy by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(!existing?.description.isNullOrBlank()) }
    var showDate by remember { mutableStateOf(false) }
    var prioMenu by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun save() {
        if (summary.isBlank() || calendarId.isBlank() || busy) return
        busy = true
        val due = dueMillis?.let { toIsoInstant(it) }
        vm.save(todoId, calendarId, summary, description, due, allDay, priority.takeIf { it != 0 }, existing?.etag) { ok ->
            busy = false; if (ok) onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (todoId != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { vm.delete(todoId) { onDismiss() } }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Title — the hero input, autofocused with the keyboard up.
            TextField(
                value = summary,
                onValueChange = { summary = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text(stringResource(R.string.todos_title_hint), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                textStyle = MaterialTheme.typography.titleLarge,
                colors = bareFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )

            if (showNotes) {
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.todos_notes_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = bareFieldColors(),
                )
            }

            // Inline metadata chips.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaChip(
                    icon = Icons.Outlined.CalendarToday,
                    label = dueMillis?.let { quickLabelFor(it) } ?: stringResource(R.string.todos_due),
                    active = dueMillis != null,
                    onClick = { showDate = true },
                )
                Box {
                    MetaChip(
                        icon = Icons.Outlined.Flag,
                        label = priorityChipLabel(priority),
                        active = priority != 0,
                        tint = if (priority != 0) priorityColor(priority) else null,
                        onClick = { prioMenu = true },
                    )
                    DropdownMenu(expanded = prioMenu, onDismissRequest = { prioMenu = false }) {
                        listOf(0, 1, 5, 9).forEach { p ->
                            DropdownMenuItem(
                                text = { Text(priorityChipLabel(p)) },
                                leadingIcon = { Icon(Icons.Outlined.Flag, null, tint = if (p == 0) MaterialTheme.colorScheme.onSurfaceVariant else priorityColor(p)) },
                                onClick = { priority = p; prioMenu = false },
                            )
                        }
                    }
                }
                if (lists.size > 1) {
                    Box {
                        MetaChip(
                            icon = Icons.Outlined.Inbox,
                            label = lists.firstOrNull { it.id == calendarId }?.name ?: stringResource(R.string.todos_list),
                            active = false,
                            onClick = { listMenu = true },
                        )
                        DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                            lists.forEach { l ->
                                DropdownMenuItem(text = { Text(l.name) }, onClick = { calendarId = l.id; listMenu = false })
                            }
                        }
                    }
                }
                if (!showNotes) {
                    MetaChip(icon = Icons.Outlined.Notes, label = stringResource(R.string.todos_description), active = false, onClick = { showNotes = true })
                }
            }

            // Send.
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = { save() },
                    enabled = summary.isNotBlank() && !busy,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Brand.accent, contentColor = Color.White),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_save)) }
            }
            Spacer(Modifier.size(4.dp))
        }
    }

    LaunchedEffect(Unit) {
        // Only autofocus on create — editing should not force the keyboard over the content.
        if (todoId == null) { focus.requestFocus(); keyboard?.show() }
    }

    if (showDate) {
        DueDateSheet(
            current = dueMillis,
            allDay = allDay,
            onPick = { m, ad -> dueMillis = m; allDay = ad; showDate = false },
            onClear = { dueMillis = null; showDate = false },
            onDismiss = { showDate = false },
        )
    }
}

/** Quick due-date picker: preset chips (today/tomorrow/weekend/next week) + a full date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDateSheet(current: Long?, allDay: Boolean, onPick: (Long?, Boolean) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var allDayState by remember { mutableStateOf(allDay) }
    var showPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.todos_due), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { onPick(dayMillis(0), allDayState) }, label = { Text(stringResource(R.string.todos_due_today)) })
                AssistChip(onClick = { onPick(dayMillis(1), allDayState) }, label = { Text(stringResource(R.string.todos_due_tomorrow)) })
                AssistChip(onClick = { onPick(weekendMillis(), allDayState) }, label = { Text(stringResource(R.string.todos_due_weekend)) })
                AssistChip(onClick = { onPick(nextWeekMillis(), allDayState) }, label = { Text(stringResource(R.string.todos_due_next_week)) })
                AssistChip(onClick = { showPicker = true }, leadingIcon = { Icon(Icons.Outlined.CalendarToday, null) }, label = { Text(stringResource(R.string.todos_due_pick)) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.todos_all_day), Modifier.weight(1f))
                Switch(checked = allDayState, onCheckedChange = { allDayState = it })
            }
            if (current != null) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.todos_due_none), color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = current)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = { showPicker = false; onPick(state.selectedDateMillis, allDayState) }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun MetaChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, tint: Color? = null, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, tint = tint ?: if (active) Brand.accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
        shape = RoundedCornerShape(10.dp),
        colors = if (active) AssistChipDefaults.assistChipColors(
            containerColor = Brand.accent.copy(alpha = 0.12f),
            labelColor = Brand.accent,
        ) else AssistChipDefaults.assistChipColors(),
    )
}

@Composable
private fun bareFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

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
private fun priorityColor(p: Int): Color = when {
    p in 1..4 -> MaterialTheme.colorScheme.error
    p == 5 -> Brand.tintOrange
    else -> Brand.tintBlue
}

@Composable
private fun priorityLabel(p: Int?): String? {
    val v = p ?: 0
    if (v == 0) return null
    return priorityChipLabel(v)
}

// ---- date helpers (UTC-midnight millis, matching the M3 DatePicker) ----
private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC)

private fun fmtDate(iso: String): String = runCatching { dateFmt.format(Instant.parse(iso)) }.getOrElse { iso.take(10) }
private fun parseMillis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
private fun toIsoInstant(millis: Long): String = Instant.ofEpochMilli(millis).toString()

private fun dayMillis(plusDays: Long): Long =
    LocalDate.now().plusDays(plusDays).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun weekendMillis(): Long =
    LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun nextWeekMillis(): Long =
    LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Human label for a due millis: Today/Tomorrow if near, else the date. */
@Composable
private fun quickLabelFor(millis: Long): String {
    val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.todos_due_today)
        today.plusDays(1) -> stringResource(R.string.todos_due_tomorrow)
        else -> dateFmt.format(Instant.ofEpochMilli(millis))
    }
}
