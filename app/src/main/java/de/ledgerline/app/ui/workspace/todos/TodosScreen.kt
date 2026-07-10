package de.ledgerline.app.ui.workspace.todos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.workspace.common.CenteredMessage
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodosScreen(modifier: Modifier = Modifier, vm: TodosViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    when {
        ui.loading -> LoadingBox(modifier)
        ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() }, modifier)
        ui.sections.isEmpty() -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) { CenteredMessage(stringResource(R.string.ws_empty_todos)) }
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
                        ListItem(
                            headlineContent = { Text(todo.title) },
                            supportingContent = { if (todo.due.isNotBlank()) Text(todo.due) },
                            leadingContent = { Checkbox(checked = todo.done, onCheckedChange = null, enabled = false) },
                        )
                    }
                }
            }
        }
    }
}
