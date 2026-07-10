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
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.TodoItem
import de.ledgerline.app.ui.workspace.common.formatDue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoDetailScreen(todo: TodoItem, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val due = formatDue(todo.due)
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
            if (todo.description.isNotBlank()) {
                Text(todo.description, style = MaterialTheme.typography.bodyLarge)
            }
            if (due.isNotBlank()) DetailRow(stringResource(R.string.todo_due), due)
            DetailRow(stringResource(R.string.todo_priority), priorityLabel(todo.priority))

            val url = todo.url.trim()
            if (url.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                }) {
                    Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.todo_open_link))
                }
            }
        }
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
