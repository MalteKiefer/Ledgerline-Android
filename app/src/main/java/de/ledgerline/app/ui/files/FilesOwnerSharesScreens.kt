package de.ledgerline.app.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.files.FileFolder
import de.ledgerline.app.domain.model.files.FileUploadLink
import de.ledgerline.app.domain.model.files.ShareView
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.ConfirmDialog
import de.ledgerline.app.ui.common.LedgerRow
import de.ledgerline.app.ui.common.ListBottomPadding
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.common.SoftIconChip
import de.ledgerline.app.ui.common.listSection
import de.ledgerline.app.ui.theme.Brand
import kotlinx.coroutines.launch

/**
 * "Shared by me": the owner-side overview of everything the caller has published — public share
 * links (`GET /files/rel-shares`, copy + revoke) and public inbound upload links
 * (`/files/upload-links`, create into a folder + copy + revoke). The member-side counterpart is
 * [SharedWithMeScreen].
 */
@Composable
fun SharedByMeScreen(vm: FilesViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by vm.data.collectAsStateWithLifecycle()

    var shares by remember { mutableStateOf<List<ShareView>?>(null) }
    var links by remember { mutableStateOf<List<FileUploadLink>?>(null) }
    var createLink by remember { mutableStateOf(false) }
    var revokeShare by remember { mutableStateOf<ShareView?>(null) }
    var revokeLink by remember { mutableStateOf<FileUploadLink?>(null) }

    suspend fun reload() {
        shares = vm.ownerShares()
        links = vm.uploadLinks()
    }
    LaunchedEffect(Unit) { reload() }

    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.shared_by_me), onBack = onBack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (shares == null || links == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (shares!!.isEmpty() && links!!.isEmpty()) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.shared_by_me_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                    if (shares!!.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.share_public_link)) }
                        listSection(shares!!, key = { "pl${it.id}" }) { s ->
                            val leadIcon = if (s.kind == "folder") Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile
                            LedgerRow(
                                title = s.name ?: "—",
                                subtitle = vm.shareUrl(s.token),
                                leading = { SoftIconChip(leadIcon, tint = Brand.tintBlue) },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { copyToClipboard(ctx, vm.shareUrl(s.token)) }) {
                                            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.share_copy_link))
                                        }
                                        IconButton(onClick = { revokeShare = s }) {
                                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.share_revoke), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                },
                            )
                        }
                    }
                    item { SectionLabel(stringResource(R.string.upload_links)) }
                    if (links!!.isEmpty()) {
                        item { Text(stringResource(R.string.upload_links_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        listSection(links!!, key = { "ul${it.id}" }) { l ->
                            LedgerRow(
                                title = l.label?.ifBlank { null } ?: (l.folderName ?: stringResource(R.string.files_root)),
                                subtitle = vm.uploadLinkUrl(l.token),
                                leading = { SoftIconChip(Icons.Outlined.UploadFile, tint = Brand.tintGreen) },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { copyToClipboard(ctx, vm.uploadLinkUrl(l.token)) }) {
                                            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.share_copy_link))
                                        }
                                        IconButton(onClick = { revokeLink = l }) {
                                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.share_revoke), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { createLink = true },
                icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                text = { Text(stringResource(R.string.upload_link_create)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            )
        }
    }

    if (createLink) CreateUploadLinkDialog(
        folders = data?.folders.orEmpty().filter { it.deletedAt == null },
        onCreate = { folderId, label, expires ->
            createLink = false
            scope.launch { vm.createUploadLink(folderId, label, expires); reload() }
        },
        onDismiss = { createLink = false },
    )
    revokeShare?.let { s ->
        ConfirmDialog(
            message = stringResource(R.string.share_revoke_confirm), confirmLabel = stringResource(R.string.share_revoke),
            onConfirm = { revokeShare = null; vm.deleteShare(s.id) { scope.launch { reload() } } },
            onDismiss = { revokeShare = null },
        )
    }
    revokeLink?.let { l ->
        ConfirmDialog(
            message = stringResource(R.string.share_revoke_confirm), confirmLabel = stringResource(R.string.share_revoke),
            onConfirm = { revokeLink = null; vm.deleteUploadLink(l.id) { scope.launch { reload() } } },
            onDismiss = { revokeLink = null },
        )
    }
}

@Composable
private fun CreateUploadLinkDialog(
    folders: List<FileFolder>,
    onCreate: (folderId: Int?, label: String?, expires: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var expires by remember { mutableStateOf("") }
    var folderId by remember { mutableStateOf<Int?>(null) }
    var pickFolder by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.upload_link_create)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text(stringResource(R.string.upload_link_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val folderName = folderId?.let { id -> folders.firstOrNull { it.id == id }?.name } ?: stringResource(R.string.files_root)
                LedgerRow(
                    title = stringResource(R.string.upload_link_folder),
                    subtitle = folderName,
                    leading = { SoftIconChip(Icons.Outlined.Folder, tint = Brand.tintBlue) },
                    onClick = { pickFolder = true },
                )
                OutlinedTextField(value = expires, onValueChange = { expires = it }, label = { Text(stringResource(R.string.share_expires)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(folderId, label.trim().ifBlank { null }, expires.trim().ifBlank { null }) }) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (pickFolder) MoveDialog(
        folders = folders,
        currentParent = folderId,
        onPick = { dest -> folderId = dest; pickFolder = false },
        onDismiss = { pickFolder = false },
    )
}
