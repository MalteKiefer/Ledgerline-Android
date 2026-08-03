package de.ledgerline.app.ui.workspace.todos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.TodoItem
import de.ledgerline.app.domain.model.TodoList
import de.ledgerline.app.domain.workspace.Tags
import de.ledgerline.app.ui.workspace.LocalFullscreen
import de.ledgerline.app.ui.workspace.common.formatDue
import de.ledgerline.app.ui.common.TextInputDialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The supported priority values, highest urgency first (web parity: high/normal/low). */
private val PRIORITIES = listOf("high", "normal", "low")

/**
 * Full-screen create/edit form for a todo. Used for both create (pass a blank
 * [initial]) and edit (pass a prefilled one). [onSave] receives the trimmed-ready
 * field values; the list picker also exposes an inline "New list…" prompt via
 * [onCreateList], which returns the new list's id so it can be selected immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoEditor(
    title: String,
    initial: TodoItem,
    lists: List<TodoList>,
    onSave: (title: String, listId: String?, priority: String, due: String, description: String, url: String, tags: List<String>) -> Unit,
    onCreateList: (name: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    // Hide the workspace scaffold chrome (top bar/insets) so the editor is full-screen
    // and doesn't get pushed down by a leftover top gap.
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }
    var todoTitle by rememberSaveable { mutableStateOf(initial.title) }
    var listId by rememberSaveable { mutableStateOf(initial.listId) }
    var priority by rememberSaveable { mutableStateOf(initial.priority.ifBlank { "normal" }) }
    var due by rememberSaveable { mutableStateOf(initial.due) }
    var description by rememberSaveable { mutableStateOf(initial.description) }
    var url by rememberSaveable { mutableStateOf(initial.url) }
    var tags by remember { mutableStateOf(initial.tags) }
    var tagDraft by rememberSaveable { mutableStateOf("") }

    var showNewList by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    // Calendar day picked in step 1, carried into the time picker (step 2) to build a
    // `yyyy-MM-ddTHH:mm` due (web/iOS parity: date + wall-clock time, no timezone).
    var pendingDate by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = todoTitle.isNotBlank(),
                        onClick = { onSave(todoTitle, listId, priority, due, description, url, Tags.mergeDraft(tags, tagDraft)) },
                    ) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = todoTitle,
                onValueChange = { todoTitle = it },
                label = { Text(stringResource(R.string.todo_title_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // List picker (No list / existing lists / New list…)
            val listName = lists.firstOrNull { it.id == listId }?.name
                ?: stringResource(R.string.todo_no_list)
            PickerField(label = stringResource(R.string.todo_list), value = listName) { dismiss ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.todo_no_list)) },
                    onClick = { listId = null; dismiss() },
                )
                lists.forEach { l ->
                    DropdownMenuItem(text = { Text(l.name) }, onClick = { listId = l.id; dismiss() })
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.todo_new_list)) },
                    onClick = { dismiss(); showNewList = true },
                )
            }

            // Priority picker
            PickerField(label = stringResource(R.string.todo_priority), value = priorityLabel(priority)) { dismiss ->
                PRIORITIES.forEach { p ->
                    DropdownMenuItem(text = { Text(priorityLabel(p)) }, onClick = { priority = p; dismiss() })
                }
            }

            // Due-date picker: a read-only field showing the formatted due date;
            // tapping opens an M3 DatePickerDialog, the × clears it.
            val dueLabel = formatDue(due).ifBlank { stringResource(R.string.todo_due_set) }
            Box(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = dueLabel,
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text(stringResource(R.string.todo_due)) },
                    trailingIcon = {
                        if (due.isNotBlank()) {
                            IconButton(onClick = { due = "" }) {
                                Icon(Icons.Outlined.Clear, stringResource(R.string.todo_due_clear))
                            }
                        } else {
                            Icon(Icons.Outlined.ArrowDropDown, null)
                        }
                    },
                    colors = de.ledgerline.app.ui.common.pickerFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Transparent overlay to capture taps (a disabled TextField swallows clicks),
                // but leave room for the trailing clear button on the right.
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(end = if (due.isNotBlank()) 48.dp else 0.dp)
                        .clickable { showDatePicker = true }
                )
            }
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.todo_description)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.todo_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            de.ledgerline.app.ui.workspace.common.TagInput(
                tags = tags,
                onTagsChange = { tags = it },
                draft = tagDraft,
                onDraftChange = { tagDraft = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showDatePicker) {
        val initialMillis = parseDueToUtcMillis(due)
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        // Step 1 → carry the picked day into the time picker (step 2).
                        pendingDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        showDatePicker = false
                        showTimePicker = true
                    } ?: run { showDatePicker = false }
                }) { Text(stringResource(R.string.action_next)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showTimePicker) {
        // Pre-select from an existing time component, else 09:00.
        val existing = due.substringAfter('T', "").takeIf { it.length >= 5 }
        val initHour = existing?.substring(0, 2)?.toIntOrNull() ?: 9
        val initMin = existing?.substring(3, 5)?.toIntOrNull() ?: 0
        val timeState = rememberTimePickerState(initialHour = initHour, initialMinute = initMin, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pendingDate?.let { d -> due = "%sT%02d:%02d".format(d, timeState.hour, timeState.minute) }
                    showTimePicker = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                // "Date only" — keep the day without a time (legacy shape still accepted).
                TextButton(onClick = {
                    pendingDate?.let { d -> due = d }
                    showTimePicker = false
                }) { Text(stringResource(R.string.todo_due_date_only)) }
            },
            text = { TimePicker(state = timeState) },
        )
    }

    if (showNewList) {
        TextInputDialog(
            title = stringResource(R.string.todo_new_list),
            label = stringResource(R.string.todo_list),
            confirmLabel = stringResource(R.string.action_create),
            initial = "",
            onConfirm = { name -> onCreateList(name); showNewList = false },
            onDismiss = { showNewList = false },
        )
    }
}

/**
 * Parse a stored `due` value (ISO date `yyyy-MM-dd` or a full ISO date-time) into
 * UTC epoch millis to pre-select the picker, or null if blank/unparseable.
 */
private fun parseDueToUtcMillis(due: String): Long? {
    if (due.isBlank()) return null
    return try {
        val date = when {
            due.contains('T') && (due.endsWith("Z") || due.contains('+')) ->
                OffsetDateTime.parse(due).toLocalDate()
            due.contains('T') -> LocalDate.parse(due.substringBefore('T'))
            else -> LocalDate.parse(due)
        }
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

/** A read-only text-field-styled trigger that opens a dropdown of [menu] items. */
@Composable
private fun PickerField(
    label: String,
    value: String,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
            colors = de.ledgerline.app.ui.common.pickerFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent overlay to capture taps (a disabled TextField swallows clicks).
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menu { expanded = false }
        }
    }
}
