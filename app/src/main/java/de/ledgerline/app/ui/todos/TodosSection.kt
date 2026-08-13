package de.ledgerline.app.ui.todos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
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
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.calendar.Calendar
import de.ledgerline.app.domain.model.calendar.CalendarTodo
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.RefreshBox
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.cardSurface
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** The "Aufgaben" (VTODO task-list) tab: lists, search, due sections, nested subtasks, quick-add editor. */
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

    var editorTarget by remember { mutableStateOf<String?>(null) } // null closed, "" create, id edit
    var addingList by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sharingList by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.tab_todos), actions = {
            selectedList?.let { lid ->
                IconButton(onClick = { sharingList = lid }) {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = stringResource(R.string.todos_share_list))
                }
            }
        })

        // Search.
        TextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text(stringResource(R.string.todos_search)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = searchFieldColors(),
        )

        // Task-list picker.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selectedList == null, { vm.selectList(null) }, label = { Text(stringResource(R.string.todos_all_lists)) })
            lists.forEach { l -> FilterChip(selectedList == l.id, { vm.selectList(l.id) }, label = { Text(l.name) }) }
            AssistChip(onClick = { addingList = true }, label = { Text(stringResource(R.string.todos_add_list)) }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
        }
        // Open / all filter.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!showDone, { if (showDone) vm.toggleShowDone() }, label = { Text(stringResource(R.string.todos_open)) })
            FilterChip(showDone, { if (!showDone) vm.toggleShowDone() }, label = { Text(stringResource(R.string.todos_all)) })
        }

        val entries = buildEntries(
            todos = todos, selectedList = selectedList, showDone = showDone, query = query.trim(),
            headers = sectionHeaders(),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RefreshBox(refreshing = refreshing, onRefresh = { vm.refresh() }) {
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.todos_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(48.dp))
                    }
                } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp, top = 4.dp)) {
                    items(entries, key = { it.key }) { e ->
                        when (e) {
                            is Entry.Header -> Text(
                                e.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (e.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                            )
                            is Entry.Item -> TodoRow(
                                todo = e.todo,
                                listName = vm.listName(e.todo.calendar).takeIf { selectedList == null },
                                indent = e.child,
                                progress = e.progress,
                                onToggle = { vm.setDone(e.todo, !e.todo.done) },
                                onClick = { editorTarget = e.todo.id },
                            )
                        }
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
            text = { TextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.todos_list)) }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.addList(name) {}; addingList = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { addingList = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    sharingList?.let { lid ->
        TodoShareDialog(vm = vm, calendarId = lid, onDismiss = { sharingList = null })
    }
}

/** Manage cross-user shares for one task list (`/calendar/shares`): list current, add by email, revoke. */
@Composable
private fun TodoShareDialog(vm: TodosViewModel, calendarId: String, onDismiss: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var shares by remember { mutableStateOf<List<de.ledgerline.app.domain.model.calendar.CalendarShare>?>(null) }
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("viewer") }
    var msg by remember { mutableStateOf<String?>(null) }
    fun reload() { scope.launch { shares = vm.shares().filter { it.calendarId == calendarId } } }
    LaunchedEffect(calendarId) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.todos_share_list)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                when (val s = shares) {
                    null -> Text(stringResource(R.string.todos_loading))
                    else -> {
                        if (s.isEmpty()) Text(stringResource(R.string.todos_share_none), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        s.forEach { share ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(share.recipient ?: "—", style = MaterialTheme.typography.bodyMedium)
                                    Text(share.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { vm.unshare(share.id) { if (it) reload() } }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                TextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.todos_share_email)) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(role == "viewer", { role = "viewer" }, label = { Text(stringResource(R.string.todos_share_viewer)) })
                    FilterChip(role == "editor", { role = "editor" }, label = { Text(stringResource(R.string.todos_share_editor)) })
                }
            }
        },
        confirmButton = {
            val failMsg = stringResource(R.string.todos_share_failed)
            TextButton(enabled = email.isNotBlank(), onClick = {
                vm.shareList(calendarId, email, role) { ok ->
                    if (ok) { email = ""; msg = null; reload() } else msg = failMsg
                }
            }) { Text(stringResource(R.string.todos_share_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

// ---------------------------------------------------------------------------
//  List model — headers + (nested) items
// ---------------------------------------------------------------------------
private sealed interface Entry {
    val key: String
    data class Header(val title: String, val overdue: Boolean, override val key: String) : Entry
    data class Item(val todo: CalendarTodo, val child: Boolean, val progress: Pair<Int, Int>?, override val key: String) : Entry
}

private data class SectionHeaders(val overdue: String, val today: String, val upcoming: String, val nodate: String, val done: String)

@Composable
private fun sectionHeaders() = SectionHeaders(
    overdue = stringResource(R.string.todos_sec_overdue),
    today = stringResource(R.string.todos_sec_today),
    upcoming = stringResource(R.string.todos_sec_upcoming),
    nodate = stringResource(R.string.todos_sec_nodate),
    done = stringResource(R.string.todos_sec_done),
)

/** Build the flat render list: search → flat matches; else due-sections with subtasks nested under parents. */
private fun buildEntries(
    todos: List<CalendarTodo>,
    selectedList: String?,
    showDone: Boolean,
    query: String,
    headers: SectionHeaders,
): List<Entry> {
    val scoped = todos.filter { selectedList == null || it.calendar == selectedList }

    if (query.isNotBlank()) {
        return scoped.filter { showDone || !it.done }
            .filter { it.summary.orEmpty().contains(query, true) || it.description.orEmpty().contains(query, true) }
            .sortedWith(compareBy({ it.done }, { it.due ?: "￿" }))
            .map { Entry.Item(it, child = false, progress = null, key = "i${it.id}") }
    }

    val byUid = scoped.filter { !it.uid.isNullOrBlank() }.associateBy { it.uid }
    val childrenOf = scoped.filter { !it.relatedTo.isNullOrBlank() && byUid.containsKey(it.relatedTo) }
        .groupBy { it.relatedTo }
    val topLevel = scoped.filter { it.relatedTo.isNullOrBlank() || !byUid.containsKey(it.relatedTo) }

    val today = LocalDate.now()
    fun bucket(t: CalendarTodo): Int = when {
        t.done -> 4
        t.due.isNullOrBlank() -> 3
        else -> {
            val d = runCatching { Instant.parse(t.due).atZone(ZoneOffset.UTC).toLocalDate() }.getOrNull()
            when { d == null -> 3; d.isBefore(today) -> 0; d == today -> 1; else -> 2 }
        }
    }
    val labels = listOf(headers.overdue, headers.today, headers.upcoming, headers.nodate, headers.done)

    val out = ArrayList<Entry>()
    for (b in 0..4) {
        if (b == 4 && !showDone) continue
        val inBucket = topLevel.filter { (showDone || !it.done) && bucket(it) == b }
            .sortedWith(compareBy({ it.sortOrder }, { it.due ?: "￿" }))
        if (inBucket.isEmpty()) continue
        out += Entry.Header(labels[b], overdue = b == 0, key = "h$b")
        for (t in inBucket) {
            val kids = childrenOf[t.uid].orEmpty().filter { showDone || !it.done }
            val allKids = childrenOf[t.uid].orEmpty()
            val progress = if (allKids.isNotEmpty()) allKids.count { it.done } to allKids.size else null
            out += Entry.Item(t, child = false, progress = progress, key = "i${t.id}")
            kids.sortedWith(compareBy({ it.sortOrder }, { it.due ?: "￿" })).forEach { c ->
                out += Entry.Item(c, child = true, progress = null, key = "i${c.id}")
            }
        }
    }
    return out
}

// ---------------------------------------------------------------------------
//  Row
// ---------------------------------------------------------------------------
@Composable
private fun TodoRow(todo: CalendarTodo, listName: String?, indent: Boolean, progress: Pair<Int, Int>?, onToggle: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 24.dp else 0.dp).cardSurface(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (indent) Icon(Icons.Outlined.SubdirectoryArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
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
            TodoMeta(todo, listName, progress)
        }
        PriorityDot(todo.priority)
    }
}

@Composable
private fun TodoMeta(todo: CalendarTodo, listName: String?, progress: Pair<Int, Int>?) {
    val overdue = !todo.done && dueIsOverdue(todo.due)
    val parts = ArrayList<Pair<String, Color>>()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    todo.due?.takeIf { it.isNotBlank() }?.let { parts += fmtDueIso(it, todo.allDay) to if (overdue) MaterialTheme.colorScheme.error else muted }
    progress?.let { parts += "☑ ${it.first}/${it.second}" to muted }
    (todo.percentComplete ?: 0).takeIf { it in 1..99 }?.let { parts += "$it%" to muted }
    priorityLabel(todo.priority)?.let { parts += it to muted }
    todo.rrule?.takeIf { it.isNotBlank() }?.let { parts += "↻" to muted }
    listName?.let { parts += it to muted }
    if (parts.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEachIndexed { i, (t, c) ->
            if (i > 0) Text("·", style = MaterialTheme.typography.bodySmall, color = muted)
            Text(t, style = MaterialTheme.typography.bodySmall, color = c)
        }
    }
}

@Composable
private fun PriorityDot(priority: Int?) {
    val p = priority ?: 0
    if (p == 0) return
    Box(Modifier.padding(end = 12.dp).size(10.dp).clip(CircleShape).background(priorityColor(p)))
}

// ---------------------------------------------------------------------------
//  Editor — quick-add sheet with full VTODO parity (start/status/repeat/progress/tags/subtask)
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var startMillis by remember { mutableStateOf(existing?.dtstart?.let { parseMillis(it) }) }
    var dueMillis by remember { mutableStateOf(existing?.due?.let { parseMillis(it) }) }
    var allDay by remember { mutableStateOf(existing?.allDay ?: false) }
    var priority by remember { mutableIntStateOf(existing?.priority ?: 0) }
    var status by remember { mutableStateOf(existing?.status ?: "NEEDS-ACTION") }
    var percent by remember { mutableIntStateOf(existing?.percentComplete ?: 0) }
    var rrule by remember { mutableStateOf(existing?.rrule) }
    val tags = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { existing?.categories?.let { addAll(it) } } }
    var parentUid by remember { mutableStateOf(existing?.relatedTo) }
    var alarmMinutes by remember { mutableStateOf(existing?.alarmMinutes) }
    var reminderMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(!existing?.description.isNullOrBlank()) }
    var advanced by remember { mutableStateOf(!existing?.rrule.isNullOrBlank() || (existing?.percentComplete ?: 0) > 0 || !existing?.dtstart.isNullOrBlank() || existing?.categories?.isNotEmpty() == true) }
    var showDue by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(false) }
    var prioMenu by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var parentMenu by remember { mutableStateOf(false) }
    var repeatMenu by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Subtask parent candidates: other top-level, non-completed tasks in the same list with a uid.
    val parentCandidates = vm.todos.value.filter {
        it.id != todoId && !it.uid.isNullOrBlank() && it.relatedTo.isNullOrBlank() && !it.done &&
            (calendarId.isBlank() || it.calendar == calendarId)
    }
    val parentSummary = parentUid?.let { uid -> vm.todos.value.firstOrNull { it.uid == uid }?.summary }

    fun save() {
        if (summary.isBlank() || calendarId.isBlank() || busy) return
        busy = true
        vm.save(
            id = todoId, calendarId = calendarId, summary = summary, description = description,
            dtstart = startMillis?.let { toIsoInstant(it) }, due = dueMillis?.let { toIsoInstant(it) },
            allDay = allDay, status = status, priority = priority.takeIf { it != 0 },
            percent = percent.takeIf { it in 1..100 }, rrule = rrule, categories = tags.toList(),
            parentUid = parentUid, alarmMinutes = alarmMinutes, etag = existing?.etag,
        ) { ok -> busy = false; if (ok) onDismiss() }
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

            TextField(
                value = summary, onValueChange = { summary = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text(stringResource(R.string.todos_title_hint), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                textStyle = MaterialTheme.typography.titleLarge,
                colors = bareFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )

            if (showNotes) {
                TextField(
                    value = description, onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.todos_notes_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    textStyle = MaterialTheme.typography.bodyMedium, colors = bareFieldColors(),
                )
            }

            // Core inline metadata chips.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MetaChip(Icons.Outlined.CalendarToday, dueMillis?.let { dueChipLabel(it, allDay) } ?: stringResource(R.string.todos_due), dueMillis != null) { showDue = true }
                Box {
                    MetaChip(Icons.Outlined.Notifications, reminderChipLabel(alarmMinutes), alarmMinutes != null) { reminderMenu = true }
                    DropdownMenu(reminderMenu, { reminderMenu = false }) {
                        reminderOptions().forEach { (min, labelRes) ->
                            DropdownMenuItem(text = { Text(stringResource(labelRes)) }, onClick = { alarmMinutes = min; reminderMenu = false })
                        }
                    }
                }
                Box {
                    MetaChip(Icons.Outlined.Flag, priorityChipLabel(priority), priority != 0, tint = if (priority != 0) priorityColor(priority) else null) { prioMenu = true }
                    DropdownMenu(prioMenu, { prioMenu = false }) {
                        listOf(0, 1, 5, 9).forEach { p ->
                            DropdownMenuItem(text = { Text(priorityChipLabel(p)) }, leadingIcon = { Icon(Icons.Outlined.Flag, null, tint = if (p == 0) MaterialTheme.colorScheme.onSurfaceVariant else priorityColor(p)) }, onClick = { priority = p; prioMenu = false })
                        }
                    }
                }
                if (lists.size > 1) Box {
                    MetaChip(Icons.Outlined.Inbox, lists.firstOrNull { it.id == calendarId }?.name ?: stringResource(R.string.todos_list), false) { listMenu = true }
                    DropdownMenu(listMenu, { listMenu = false }) {
                        lists.forEach { l -> DropdownMenuItem(text = { Text(l.name) }, onClick = { calendarId = l.id; listMenu = false }) }
                    }
                }
                Box {
                    MetaChip(Icons.Outlined.SubdirectoryArrowRight, parentSummary ?: stringResource(R.string.todos_subtask_of), parentUid != null) { parentMenu = true }
                    DropdownMenu(parentMenu, { parentMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.todos_subtask_none)) }, onClick = { parentUid = null; parentMenu = false })
                        parentCandidates.forEach { c -> DropdownMenuItem(text = { Text(c.summary.orEmpty().ifBlank { "—" }) }, onClick = { parentUid = c.uid; parentMenu = false }) }
                    }
                }
                if (!showNotes) MetaChip(Icons.Outlined.Notes, stringResource(R.string.todos_description), false) { showNotes = true }
            }

            // Advanced (web parity): start / status / repeat / progress / tags.
            TextButton(onClick = { advanced = !advanced }) {
                Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(if (advanced) R.string.todos_less else R.string.todos_more))
            }
            if (advanced) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Start date.
                    FieldLabel(stringResource(R.string.todos_start))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(onClick = { showStart = true }, leadingIcon = { Icon(Icons.Outlined.CalendarToday, null, Modifier.size(18.dp)) }, label = { Text(startMillis?.let { fmtMillis(it) } ?: stringResource(R.string.todos_start)) }, shape = RoundedCornerShape(10.dp))
                        if (startMillis != null) TextButton(onClick = { startMillis = null }) { Text(stringResource(R.string.todos_due_none), color = MaterialTheme.colorScheme.error) }
                    }
                    // Status.
                    FieldLabel(stringResource(R.string.todos_status))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        statusOptions().forEach { (code, labelRes) ->
                            FilterChip(status == code, { status = code; if (code == "COMPLETED") percent = 100 }, label = { Text(stringResource(labelRes)) })
                        }
                    }
                    // Repeat.
                    FieldLabel(stringResource(R.string.todos_repeat))
                    Box {
                        AssistChip(onClick = { repeatMenu = true }, label = { Text(stringResource(repeatLabelRes(rrule))) }, shape = RoundedCornerShape(10.dp))
                        DropdownMenu(repeatMenu, { repeatMenu = false }) {
                            repeatOptions().forEach { (value, labelRes) ->
                                DropdownMenuItem(text = { Text(stringResource(labelRes)) }, onClick = { rrule = value; repeatMenu = false })
                            }
                        }
                    }
                    // Progress.
                    FieldLabel("${stringResource(R.string.todos_progress)} — $percent%")
                    Slider(value = percent / 100f, onValueChange = { percent = (it * 100).roundToInt() }, steps = 19)
                    // Tags.
                    FieldLabel(stringResource(R.string.todos_tags))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { tag ->
                            InputChip(selected = false, onClick = { tags.remove(tag) }, label = { Text(tag) }, trailingIcon = { Icon(Icons.Outlined.Delete, null, Modifier.size(16.dp)) })
                        }
                    }
                    TextField(
                        value = tagInput, onValueChange = { tagInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.todos_tag_add)) }, singleLine = true,
                        shape = RoundedCornerShape(12.dp), colors = bareFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            val t = tagInput.trim()
                            if (t.isNotEmpty() && t !in tags) tags.add(t); tagInput = ""
                        }),
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = { save() }, enabled = summary.isNotBlank() && !busy,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Brand.accent, contentColor = Color.White),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_save)) }
            }
            Spacer(Modifier.size(4.dp))
        }
    }

    LaunchedEffect(Unit) { if (todoId == null) { focus.requestFocus(); keyboard?.show() } }

    if (showDue) DueDateSheet(dueMillis, allDay, { m, ad -> dueMillis = m; allDay = ad; showDue = false }, { dueMillis = null; showDue = false }, { showDue = false })
    if (showStart) {
        val state = rememberDatePickerState(initialSelectedDateMillis = startMillis)
        DatePickerDialog(
            onDismissRequest = { showStart = false },
            confirmButton = { TextButton(onClick = { startMillis = state.selectedDateMillis; showStart = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { showStart = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

/** Due picker: preset chips set the date; a date picker + a time picker (when not all-day) refine it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDateSheet(current: Long?, allDay: Boolean, onPick: (Long?, Boolean) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val zone = java.time.ZoneId.systemDefault()
    var allDayState by remember { mutableStateOf(allDay) }
    var date by remember {
        mutableStateOf(current?.let { m ->
            if (allDay) Instant.ofEpochMilli(m).atZone(ZoneOffset.UTC).toLocalDate()
            else Instant.ofEpochMilli(m).atZone(zone).toLocalDate()
        })
    }
    var time by remember {
        mutableStateOf(if (!allDay && current != null) Instant.ofEpochMilli(current).atZone(zone).toLocalTime() else java.time.LocalTime.of(9, 0))
    }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    fun compute(): Long? {
        val d = date ?: return null
        return if (allDayState) d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        else java.time.LocalDateTime.of(d, time).atZone(zone).toInstant().toEpochMilli()
    }
    val timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.todos_due), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { date = LocalDate.now() }, label = { Text(stringResource(R.string.todos_due_today)) })
                AssistChip(onClick = { date = LocalDate.now().plusDays(1) }, label = { Text(stringResource(R.string.todos_due_tomorrow)) })
                AssistChip(onClick = { date = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)) }, label = { Text(stringResource(R.string.todos_due_weekend)) })
                AssistChip(onClick = { date = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)) }, label = { Text(stringResource(R.string.todos_due_next_week)) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { showDate = true },
                    leadingIcon = { Icon(Icons.Outlined.CalendarToday, null, Modifier.size(18.dp)) },
                    label = { Text(date?.let { dateFmt.format(it.atStartOfDay(ZoneOffset.UTC).toInstant()) } ?: stringResource(R.string.todos_due_pick)) },
                    shape = RoundedCornerShape(10.dp),
                )
                if (!allDayState) AssistChip(
                    onClick = { showTime = true },
                    leadingIcon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)) },
                    label = { Text(timeFmt.format(time)) },
                    shape = RoundedCornerShape(10.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.todos_all_day), Modifier.weight(1f))
                Switch(checked = allDayState, onCheckedChange = { allDayState = it })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (current != null) TextButton(onClick = onClear) { Text(stringResource(R.string.todos_due_none), color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.weight(1f))
                TextButton(enabled = date != null, onClick = { onPick(compute(), allDayState) }) { Text(stringResource(R.string.action_save)) }
            }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = state) }
    }
    if (showTime) {
        val ts = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = { TextButton(onClick = { time = java.time.LocalTime.of(ts.hour, ts.minute); showTime = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state = ts) },
        )
    }
}

@Composable
private fun MetaChip(icon: ImageVector, label: String, active: Boolean, tint: Color? = null, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, tint = tint ?: if (active) Brand.accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
        shape = RoundedCornerShape(10.dp),
        colors = if (active) AssistChipDefaults.assistChipColors(containerColor = Brand.accent.copy(alpha = 0.12f), labelColor = Brand.accent) else AssistChipDefaults.assistChipColors(),
    )
}

@Composable
private fun FieldLabel(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)

@Composable
private fun searchFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

@Composable
private fun bareFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

// ---- status / repeat / priority mapping ----
private fun statusOptions() = listOf(
    "NEEDS-ACTION" to R.string.todos_status_open,
    "IN-PROCESS" to R.string.todos_status_progress,
    "COMPLETED" to R.string.todos_status_done,
    "CANCELLED" to R.string.todos_status_cancelled,
)

private fun repeatOptions() = listOf(
    null to R.string.todos_repeat_none,
    "FREQ=DAILY" to R.string.todos_repeat_daily,
    "FREQ=WEEKLY" to R.string.todos_repeat_weekly,
    "FREQ=MONTHLY" to R.string.todos_repeat_monthly,
    "FREQ=YEARLY" to R.string.todos_repeat_yearly,
)

// Reminder presets (VALARM minutes-before due), mirroring the web task editor.
private fun reminderOptions(): List<Pair<Int?, Int>> = listOf(
    null to R.string.todos_reminder_none,
    0 to R.string.todos_reminder_at,
    5 to R.string.todos_reminder_5m,
    15 to R.string.todos_reminder_15m,
    30 to R.string.todos_reminder_30m,
    60 to R.string.todos_reminder_1h,
    1440 to R.string.todos_reminder_1d,
)

@Composable
private fun reminderChipLabel(min: Int?): String = stringResource(
    when (min) {
        null -> R.string.todos_reminder
        0 -> R.string.todos_reminder_at
        5 -> R.string.todos_reminder_5m
        15 -> R.string.todos_reminder_15m
        30 -> R.string.todos_reminder_30m
        60 -> R.string.todos_reminder_1h
        1440 -> R.string.todos_reminder_1d
        else -> R.string.todos_reminder
    },
)

private fun repeatLabelRes(rrule: String?): Int = when {
    rrule.isNullOrBlank() -> R.string.todos_repeat_none
    rrule.contains("FREQ=DAILY") -> R.string.todos_repeat_daily
    rrule.contains("FREQ=WEEKLY") -> R.string.todos_repeat_weekly
    rrule.contains("FREQ=MONTHLY") -> R.string.todos_repeat_monthly
    rrule.contains("FREQ=YEARLY") -> R.string.todos_repeat_yearly
    else -> R.string.todos_repeat_custom
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

// ---- date/time helpers ----
// All-day due/start = a calendar date pinned at UTC-midnight (matches the M3 DatePicker + the
// server's date semantics). A timed due = an absolute instant; we render/pick it in the device zone.
private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC)
private val dateTimeFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(java.time.ZoneId.systemDefault())

private fun fmtMillis(millis: Long): String = dateFmt.format(Instant.ofEpochMilli(millis))
private fun fmtDate(iso: String): String = runCatching { dateFmt.format(Instant.parse(iso)) }.getOrElse { iso.take(10) }

/** Format a due ISO: date only when all-day, else date + local time. */
private fun fmtDueIso(iso: String, allDay: Boolean): String = runCatching {
    val inst = parseInstant(iso)!!
    if (allDay) dateFmt.format(inst) else dateTimeFmt.format(inst)
}.getOrElse { iso.take(if (allDay) 10 else 16) }

/** Lenient ISO parse: full instant, or a bare date (all-day) → UTC midnight. */
private fun parseInstant(iso: String): Instant? =
    runCatching { Instant.parse(iso) }.getOrNull()
        ?: runCatching { LocalDate.parse(iso.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()

private fun parseMillis(iso: String): Long? = parseInstant(iso)?.toEpochMilli()
private fun toIsoInstant(millis: Long): String = Instant.ofEpochMilli(millis).toString()
private fun dueIsOverdue(iso: String?): Boolean {
    if (iso.isNullOrBlank()) return false
    val inst = parseInstant(iso) ?: return false
    return inst.isBefore(Instant.now())
}

/** Chip label for the editor: Today/Tomorrow shorthand (+ local time when timed), else the date. */
@Composable
private fun dueChipLabel(millis: Long, allDay: Boolean): String {
    val zone = if (allDay) ZoneOffset.UTC else java.time.ZoneId.systemDefault()
    val zdt = Instant.ofEpochMilli(millis).atZone(zone)
    val today = LocalDate.now()
    val datePart = when (zdt.toLocalDate()) {
        today -> stringResource(R.string.todos_due_today)
        today.plusDays(1) -> stringResource(R.string.todos_due_tomorrow)
        else -> dateFmt.format(Instant.ofEpochMilli(if (allDay) millis else zdt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
    }
    return if (allDay) datePart else "$datePart ${java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(zdt)}"
}
