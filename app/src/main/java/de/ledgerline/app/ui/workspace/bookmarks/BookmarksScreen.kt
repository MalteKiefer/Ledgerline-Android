package de.ledgerline.app.ui.workspace.bookmarks

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.RefreshableMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(modifier: Modifier = Modifier, vm: BookmarksViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun open(url: String) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        }
    }
    when {
        ui.loading -> LoadingBox(modifier)
        ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() }, modifier)
        ui.groups.isEmpty() -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) { RefreshableMessage(stringResource(R.string.ws_empty_bookmarks)) }
        else -> PullToRefreshBox(ui.loading, { vm.refresh() }, modifier) {
            LazyColumn(Modifier.fillMaxSize()) {
                ui.groups.forEach { group ->
                    item(key = "h-${group.folderName ?: "_"}") {
                        Text(
                            group.folderName ?: "Other",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp),
                        )
                        HorizontalDivider()
                    }
                    items(group.bookmarks, key = { it.id }) { b ->
                        ListItem(
                            headlineContent = { Text(b.title.ifBlank { b.url }) },
                            supportingContent = { Text(b.url, maxLines = 1) },
                            modifier = Modifier.fillMaxWidth().clickable { open(b.url) },
                        )
                    }
                }
            }
        }
    }
}
