package de.ledgerline.app.ui.workspace.todos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.RefreshableMessage
import de.ledgerline.app.ui.workspace.common.formatDue
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodosScreen(modifier: Modifier = Modifier, vm: TodosViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    when {
        ui.loading -> LoadingBox(modifier)
        ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() }, modifier)
        ui.sections.isEmpty() -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) { RefreshableMessage(stringResource(R.string.ws_empty_todos)) }
        else -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) {
            LazyColumn(Modifier.fillMaxSize()) {
                ui.sections.forEach { section ->
                    item(key = "h-${section.listName}") {
                        Text(
                            section.listName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp),
                        )
                        HorizontalDivider()
                    }
                    items(section.items, key = { it.id }) { todo ->
                        val dueText = formatDue(todo.due)
                        val showPriority = todo.priority.isNotBlank() && todo.priority != "normal"
                        ListItem(
                            headlineContent = {
                                Text(
                                    todo.title,
                                    textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                                    color = if (todo.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            supportingContent = {
                                if (todo.description.isNotBlank() || dueText.isNotBlank() || showPriority) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (todo.description.isNotBlank()) {
                                            Text(
                                                todo.description,
                                                maxLines = 2,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (dueText.isNotBlank() || showPriority) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                if (dueText.isNotBlank()) {
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Icon(
                                                            Icons.Outlined.Event,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                        Text(
                                                            dueText,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                                if (showPriority) {
                                                    Text(
                                                        todo.priority.replaceFirstChar { it.titlecase(Locale.getDefault()) },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                        modifier = Modifier
                                                            .background(
                                                                MaterialTheme.colorScheme.tertiaryContainer,
                                                                RoundedCornerShape(8.dp),
                                                            )
                                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            leadingContent = { Checkbox(checked = todo.done, onCheckedChange = null, enabled = false) },
                            trailingContent = {
                                if (todo.marked) {
                                    Icon(Icons.Outlined.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
