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

    val message by vm.message.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message?.let { key ->
            val text = when (key) {
                "invite_ok" -> stringResource(R.string.vaults_invite_ok)
                "invite_not_found" -> stringResource(R.string.vaults_invite_not_found)
                "member_removed" -> stringResource(R.string.vaults_member_removed)
                "vault_created" -> stringResource(R.string.vaults_created)
                else -> stringResource(R.string.vaults_action_failed)
            }
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = vm::clearMessage) { Text("×") }
            }
        }
        when {
            s.opened != null && s.openedVault != null -> VaultContentView(s.opened!!, s.openedVault!!, vm, onBack = vm::close)
            s.loading -> Centered { CircularProgressIndicator() }
            else -> VaultList(s.vaults, s.busy, s.openError, onAccept = vm::accept, onOpen = vm::open, onRefresh = vm::refresh, onCreate = vm::createVault)
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
    onCreate: (String, String) -> Unit,
) {
    val visible = vaults.filter { it.status != "revoked" }
    var showCreate by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.vaults_title), style = MaterialTheme.typography.titleMedium)
        Button(onClick = { showCreate = true }, enabled = !busy) { Text(stringResource(R.string.vaults_create)) }
    }
    if (showCreate) CreateVaultDialog(onDismiss = { showCreate = false }, onCreate = { kind, name -> showCreate = false; onCreate(kind, name) })
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
private fun VaultContentView(content: SharedVaultContent, vault: SharedVault, vm: SharedVaultsViewModel, onBack: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(content.name.ifBlank { stringResource(R.string.vaults_untitled) }, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.vaults_back)) }
    }
    if (content.kind == "password") {
        Text(stringResource(R.string.vaults_secret_count, content.secretNames.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (content.secretNames.isNotEmpty()) Column {
            content.secretNames.take(200).forEach { n -> ListItem(headlineContent = { Text(n) }); HorizontalDivider() }
        }
    } else {
        FileList(content.files, vm)
    }
    if (vault.role == "manager" || vault.owner) MembersSection(vault, vm)
}

/** Owner/manager member management: invite by email, list members, change role, remove (rotate). */
@Composable
private fun MembersSection(vault: SharedVault, vm: SharedVaultsViewModel) {
    val members by vm.members.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(vault.vaultId) { vm.loadMembers(vault) }
    Text(stringResource(R.string.vaults_members), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("viewer") }
    androidx.compose.material3.OutlinedTextField(
        value = email, onValueChange = { email = it },
        label = { Text(stringResource(R.string.vaults_invite_email)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RolePicker(role) { role = it }
        Button(onClick = { if (email.isNotBlank()) { vm.invite(vault, email.trim(), role); email = "" } }, enabled = email.isNotBlank()) {
            Text(stringResource(R.string.vaults_invite))
        }
    }
    members.forEach { m ->
        ListItem(
            headlineContent = { Text(m.email ?: m.name ?: "#${m.userId}") },
            supportingContent = { Text("${m.role} · ${m.status}") },
            trailingContent = {
                if (m.id != vault.membershipId) TextButton(onClick = { vm.removeMember(vault, m.id) }) {
                    Text(stringResource(R.string.vaults_remove), color = MaterialTheme.colorScheme.error)
                }
            },
        )
        HorizontalDivider()
    }
}

/** A tiny 3-way role selector (viewer/editor/manager). */
@Composable
private fun RolePicker(role: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(role) }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("viewer", "editor", "manager").forEach { r ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(r) }, onClick = { onChange(r); open = false })
            }
        }
    }
}

@Composable
private fun CreateVaultDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("folder") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vaults_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.vaults_name)) }, singleLine = true)
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = kind == "folder", onClick = { kind = "folder" }, label = { Text(stringResource(R.string.vaults_kind_folder)) })
                    androidx.compose.material3.FilterChip(selected = kind == "password", onClick = { kind = "password" }, label = { Text(stringResource(R.string.vaults_kind_password)) })
                }
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onCreate(kind, name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.vaults_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
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
