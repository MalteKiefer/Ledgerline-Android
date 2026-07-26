package de.ledgerline.app.ui.workspace.todos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.core.Dates
import de.ledgerline.app.data.DateFormatPref
import de.ledgerline.app.domain.model.TodoItem
import de.ledgerline.app.ui.common.ConfirmDialog
import de.ledgerline.app.ui.common.openUrl
import de.ledgerline.app.ui.workspace.LocalFullscreen
import de.ledgerline.app.ui.workspace.common.DetailHero
import de.ledgerline.app.ui.workspace.common.DetailQuickAction
import de.ledgerline.app.ui.workspace.common.InfoCard
import de.ledgerline.app.ui.workspace.common.InfoRow
import de.ledgerline.app.ui.workspace.common.RowDivider
import de.ledgerline.app.ui.workspace.common.TagChips
import de.ledgerline.app.ui.workspace.common.isOverdue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoDetailScreen(
    todo: TodoItem,
    listName: String?,
    linkChooser: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    dateFormat: DateFormatPref = DateFormatPref.SYSTEM,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }
    val context = LocalContext.current
    val due = Dates.format(todo.due, dateFormat)
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_todos)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, stringResource(R.string.todo_edit))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.todo_delete))
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
                .padding(bottom = 28.dp),
        ) {
            DetailHero(
                title = todo.title.ifBlank { stringResource(R.string.contact_untitled) },
                subtitle = listName,
                leading = {
                    Icon(
                        if (todo.done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                },
            )

            val url = todo.url.trim()
            if (url.startsWith("http://") || url.startsWith("https://")) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                ) {
                    DetailQuickAction(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.todo_open_link)) {
                        openUrl(context, url, linkChooser)
                    }
                }
            }

            InfoCard {
                InfoRow(
                    Icons.Outlined.CheckCircle,
                    stringResource(if (todo.done) R.string.todo_done else R.string.todo_not_done),
                    stringResource(R.string.todo_done),
                )
                listName?.let {
                    RowDivider()
                    InfoRow(Icons.AutoMirrored.Outlined.List, it, stringResource(R.string.todo_list))
                }
                RowDivider()
                InfoRow(Icons.Outlined.Flag, priorityLabel(todo.priority), stringResource(R.string.todo_priority))
                if (due.isNotBlank()) {
                    RowDivider()
                    val overdue = !todo.done && isOverdue(todo.due)
                    InfoRow(
                        Icons.Outlined.Schedule,
                        due,
                        stringResource(R.string.todo_due),
                        valueColor = if (overdue) MaterialTheme.colorScheme.error else null,
                    )
                }
            }

            if (todo.description.isNotBlank()) {
                InfoCard {
                    InfoRow(Icons.Outlined.Notes, todo.description, null)
                }
            }

            if (todo.tags.isNotEmpty()) {
                TagChips(todo.tags, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            message = stringResource(R.string.todo_delete),
            confirmLabel = stringResource(R.string.todo_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
