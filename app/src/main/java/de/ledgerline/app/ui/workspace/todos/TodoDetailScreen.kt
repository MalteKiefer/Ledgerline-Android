package de.ledgerline.app.ui.workspace.todos

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.TodoItem
import de.ledgerline.app.ui.workspace.common.formatDue
import de.ledgerline.app.ui.workspace.files.ConfirmDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoDetailScreen(
    todo: TodoItem,
    listName: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val due = formatDue(todo.due)
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        todo.title.ifBlank { "(untitled)" },
                        textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                    )
                },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DetailRow(
                stringResource(R.string.todo_done),
                stringResource(if (todo.done) R.string.todo_done else R.string.todo_not_done),
            )
            listName?.let { DetailRow(stringResource(R.string.todo_list), it) }
            DetailRow(stringResource(R.string.todo_priority), priorityLabel(todo.priority))
            if (due.isNotBlank()) DetailRow(stringResource(R.string.todo_due), due)
            if (todo.description.isNotBlank()) {
                Text(todo.description, style = MaterialTheme.typography.bodyLarge)
            }

            val url = todo.url.trim()
            if (url.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.todo_open_link))
                }
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

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
