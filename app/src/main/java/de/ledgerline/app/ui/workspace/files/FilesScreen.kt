package de.ledgerline.app.ui.workspace.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.RefreshableMessage
import de.ledgerline.app.ui.workspace.common.humanSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(modifier: Modifier = Modifier, vm: FilesViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var openId by remember { mutableStateOf<String?>(null) }

    val current = openId
    if (current != null) {
        val file = vm.fileById(current)
        if (file != null) { FileDetailScreen(file, onBack = { openId = null }, modifier = modifier); return }
    }
    when {
        ui.loading -> LoadingBox(modifier)
        ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() }, modifier)
        else -> PullToRefreshBox(isRefreshing = ui.loading, onRefresh = { vm.refresh() }, modifier = modifier) {
            if (ui.folders.isEmpty() && ui.files.isEmpty() && !ui.canGoBack) {
                RefreshableMessage(stringResource(R.string.ws_empty_files))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (ui.canGoBack) item {
                        ListItem(
                            headlineContent = { Text("..") },
                            leadingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) },
                            modifier = Modifier.fillMaxWidth().clickable { vm.back() },
                        )
                    }
                    items(ui.folders, key = { it.id }) { f ->
                        ListItem(
                            headlineContent = { Text(f.name) },
                            leadingContent = { Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.fillMaxWidth().clickable { vm.open(f.id) },
                        )
                    }
                    items(ui.files, key = { it.id }) { file ->
                        ListItem(
                            headlineContent = { Text(file.name) },
                            supportingContent = { Text(humanSize(file.size)) },
                            leadingContent = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null) },
                            modifier = Modifier.fillMaxWidth().clickable { openId = file.id },
                        )
                    }
                }
            }
        }
    }
}
