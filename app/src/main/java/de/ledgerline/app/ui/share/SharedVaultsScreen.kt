package de.ledgerline.app.ui.share

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.data.SharedVault
import de.ledgerline.app.data.SharedVaultContent
import de.ledgerline.app.data.VaultFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cross-user shared vaults: list, accept invites, open + view a vault's contents. */
@Composable
fun SharedVaultsContent(padding: PaddingValues) {
    val vm: SharedVaultsViewModel = hiltViewModel()
    val s by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            s.opened != null && s.openedVault != null -> VaultContentView(s.opened!!, vm, onBack = vm::close)
            s.loading -> Centered { CircularProgressIndicator() }
            else -> VaultList(s.vaults, s.busy, s.openError, onAccept = vm::accept, onOpen = vm::open, onRefresh = vm::refresh)
        }
    }
}

@Composable
private fun VaultList(
    vaults: List<SharedVault>,
    busy: Boolean,
    openError: Boolean,
    onAccept: (SharedVault) -> Unit,
    onOpen: (SharedVault) -> Unit,
    onRefresh: () -> Unit,
) {
    val visible = vaults.filter { it.status != "revoked" }
    Text(stringResource(R.string.vaults_title), style = MaterialTheme.typography.titleMedium)
    if (openError) Text(stringResource(R.string.vaults_open_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (visible.isEmpty()) {
        Text(stringResource(R.string.vaults_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.vaults_refresh)) }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(visible, key = { it.membershipId }) { v ->
            val kindLabel = if (v.kind == "password") stringResource(R.string.vaults_kind_password) else stringResource(R.string.vaults_kind_folder)
            ListItem(
                headlineContent = { Text(kindLabel) },
                supportingContent = {
                    val role = stringResource(R.string.vaults_role, v.role)
                    val badge = if (v.owner) stringResource(R.string.vaults_owner) else if (v.status == "pending") stringResource(R.string.vaults_pending) else stringResource(R.string.vaults_active)
                    Text("$role · $badge")
                },
                trailingContent = {
                    when {
                        v.status == "pending" -> Button(onClick = { onAccept(v) }, enabled = !busy) { Text(stringResource(R.string.vaults_accept)) }
                        v.canOpen -> TextButton(onClick = { onOpen(v) }, enabled = !busy) { Text(stringResource(R.string.vaults_open)) }
                        else -> {}
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun VaultContentView(content: SharedVaultContent, vm: SharedVaultsViewModel, onBack: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(content.name.ifBlank { stringResource(R.string.vaults_untitled) }, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.vaults_back)) }
    }
    if (content.kind == "password") {
        Text(stringResource(R.string.vaults_secret_count, content.secretNames.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn {
            items(content.secretNames) { n ->
                ListItem(headlineContent = { Text(n) })
                HorizontalDivider()
            }
        }
    } else {
        FileList(content.files, vm)
    }
}

@Composable
private fun FileList(files: List<VaultFile>, vm: SharedVaultsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<VaultFile?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = pending; pending = null
        if (uri != null && f != null) scope.launch {
            val bytes = vm.fileBytes(f) ?: return@launch
            withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } } }
        }
    }
    Text(stringResource(R.string.share_file_count, files.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn {
        items(files, key = { it.ref }) { f ->
            ListItem(
                headlineContent = { Text(f.name) },
                supportingContent = { Text(listOf(f.path.takeIf { it.isNotBlank() }, humanSize(f.size)).filterNotNull().joinToString(" · ")) },
                trailingContent = { OutlinedButton(onClick = { pending = f; saver.launch(f.name) }) { Text(stringResource(R.string.share_save)) } },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024; var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}
