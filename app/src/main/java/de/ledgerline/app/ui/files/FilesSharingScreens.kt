package de.ledgerline.app.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.files.ShareMember
import de.ledgerline.app.domain.model.files.ShareView
import de.ledgerline.app.domain.model.files.SharedWithMe
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.DocOpener
import de.ledgerline.app.ui.common.LedgerRow
import de.ledgerline.app.ui.common.ListBottomPadding
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.common.SoftIconChip
import de.ledgerline.app.ui.common.listSection
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.PrimaryGradientButton
import de.ledgerline.app.ui.theme.cardSurface
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Public-link share dialog for a file or folder: create a link (optional password + allow-download),
 * show + copy the URL, and revoke. For folders it also hosts cross-user member management.
 */
@Composable
fun ShareDialog(vm: FilesViewModel, kind: String, id: Int, folderId: Int?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var share by remember { mutableStateOf<ShareView?>(null) }
    var password by remember { mutableStateOf("") }
    var allowDownload by remember { mutableStateOf(true) }
    var expires by remember { mutableStateOf("") } // ISO yyyy-MM-dd, blank = never
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_public_link)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val s = share
                if (s == null) {
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.share_password_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = expires, onValueChange = { expires = it }, label = { Text(stringResource(R.string.share_expires)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.share_allow_download), Modifier.weight(1f))
                        Switch(checked = allowDownload, onCheckedChange = { allowDownload = it })
                    }
                } else {
                    val url = vm.shareUrl(s.token)
                    Text(stringResource(R.string.share_link_created), color = MaterialTheme.colorScheme.primary)
                    SelectionContainer { Text(url, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().cardSurface()) }
                    TextButton(onClick = { copyToClipboard(ctx, url) }) { Text(stringResource(R.string.share_copy_link)) }
                    // Edit the just-created share (optimistic version guard).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.share_allow_download), Modifier.weight(1f))
                        Switch(checked = allowDownload, onCheckedChange = { newVal ->
                            allowDownload = newVal
                            scope.launch {
                                val updated = vm.updateShare(s.id, kotlinx.serialization.json.buildJsonObject {
                                    put("allow_download", newVal); put("version", s.version)
                                })
                                if (updated != null) share = updated
                            }
                        })
                    }
                }
                if (kind == "folder" && folderId != null) ShareMembersSection(vm, kind = "folder", targetId = folderId)
                else if (kind == "file") ShareMembersSection(vm, kind = "file", targetId = id)
            }
        },
        confirmButton = {
            val s = share
            if (s == null) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val exp = expires.trim().ifBlank { null }
                        share = if (kind == "file") vm.createFileShare(id, password, allowDownload, exp)
                        else vm.createFolderShare(id, password, allowDownload, exp)
                        busy = false
                    }
                }) { Text(stringResource(R.string.share_create_link)) }
            } else {
                TextButton(onClick = { vm.deleteShare(s.id) { share = null; onDismiss() } }) {
                    Text(stringResource(R.string.share_revoke), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/**
 * Cross-user membership for a folder subtree OR a single file (server `kind=file|folder`):
 * list members + add by email with a role. Reuses the generic `/folder-shares/{id}/members`
 * endpoints; only the create call differs by [kind].
 */
@Composable
private fun ShareMembersSection(vm: FilesViewModel, kind: String, targetId: Int) {
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<ShareMember>>(emptyList()) }
    var shareId by remember { mutableStateOf<Int?>(null) }
    var email by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    suspend fun reload() {
        val fs = vm.folderShares().firstOrNull {
            if (kind == "file") it.kind == "file" && it.fileId == targetId else it.folderId == targetId
        }
        shareId = fs?.id; members = fs?.members.orEmpty()
    }
    LaunchedEffect(kind, targetId) { reload() }

    SectionLabel(stringResource(if (kind == "file") R.string.share_manage_file else R.string.share_manage_folder))
    members.forEach { m ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.name ?: m.email ?: "—", style = MaterialTheme.typography.bodyMedium)
                Text(if (m.role == "editor") stringResource(R.string.share_role_editor) else stringResource(R.string.share_role_viewer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { shareId?.let { sid -> vm.removeFolderShareMember(sid, m.userId) { scope.launch { reload() } } } }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text(stringResource(R.string.share_user_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.share_role_editor), Modifier.weight(1f))
        Switch(checked = editor, onCheckedChange = { editor = it })
    }
    if (error) Text(stringResource(R.string.share_member_not_found), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    TextButton(enabled = email.isNotBlank(), onClick = {
        scope.launch {
            val role = if (editor) "editor" else "viewer"
            val res = if (kind == "file") vm.createUserFileShare(targetId, email.trim(), role)
            else vm.createUserFolderShare(targetId, email.trim(), role)
            if (res != null) { email = ""; error = false; reload() } else error = true
        }
    }) { Text(stringResource(R.string.share_add_member)) }
}

/** Browse folders/files shared with me: list shares → browse → open/download. */
@Composable
fun SharedWithMeScreen(vm: FilesViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var shares by remember { mutableStateOf<List<SharedWithMe>?>(null) }
    var open by remember { mutableStateOf<SharedWithMe?>(null) }
    LaunchedEffect(Unit) { shares = vm.sharedWithMe() }

    open?.let { s ->
        SharedBrowseScreen(vm, s, onBack = { open = null }); return
    }

    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.shared_with_me), onBack = onBack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                shares == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                shares!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.shared_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                    listSection(shares!!, key = { "sh${it.id}" }) { s ->
                        LedgerRow(
                            title = s.folderName,
                            subtitle = stringResource(R.string.shared_by, s.owner.name.ifBlank { s.owner.email }),
                            leading = { SoftIconChip(Icons.Outlined.Folder, tint = Brand.tintBlue) },
                            onClick = { open = s },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedBrowseScreen(vm: FilesViewModel, share: SharedWithMe, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var browse by remember { mutableStateOf<de.ledgerline.app.domain.model.files.SharedBrowse?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(share.id) { browse = vm.browseShared(share.id) }

    AppScaffold(topBar = { AppTopBar(title = share.folderName, onBack = onBack) }) { pad ->
        val b = browse
        // A lone-file share (kind=file) carries the single file under `file`; fold it into the list.
        val files = b?.let { it.file?.let { f -> listOf(f) } ?: it.files } ?: emptyList()
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                b == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                files.isEmpty() && b.folders.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.files_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                    if (b.folders.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.files_section_folders)) }
                        listSection(b.folders, key = { "sf${it.id}" }) { f ->
                            LedgerRow(title = f.name, leading = { SoftIconChip(Icons.Outlined.Folder, tint = Brand.tintBlue) })
                        }
                    }
                    if (files.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.files_section_files)) }
                        listSection(files, key = { "sfl${it.id}" }) { f ->
                            LedgerRow(
                                title = f.name,
                                subtitle = formatBytes(f.size),
                                leading = { SoftIconChip(Icons.AutoMirrored.Outlined.InsertDriveFile, tint = Brand.tintGray) },
                                onClick = {
                                    scope.launch {
                                        busy = ctx.getString(R.string.files_downloading)
                                        val file = vm.downloadSharedToCache(share.id, f)
                                        busy = null
                                        if (file == null || !DocOpener.openFile(ctx, file, f.mime ?: "*/*")) busy = ctx.getString(R.string.files_open_failed)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            busy?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) }
        }
    }
}

internal fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("link", text))
}
