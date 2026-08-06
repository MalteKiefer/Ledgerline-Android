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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.data.SharedFile
import de.ledgerline.app.data.SharedManifest
import de.ledgerline.app.ui.common.secretKeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Paste-a-link recipient viewer for public shares (files/folders). */
@Composable
fun SharedLinkContent(padding: PaddingValues) {
    val vm: SharedLinkViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = state) {
            is SharedLinkViewModel.State.Idle -> LinkInput(onOpen = vm::open)
            is SharedLinkViewModel.State.Loading -> Centered { CircularProgressIndicator() }
            is SharedLinkViewModel.State.NeedsPassword -> PasswordPrompt(s, onSubmit = { pw -> vm.submitPassword(s.link, pw) }, onCancel = vm::reset)
            is SharedLinkViewModel.State.Ready -> ManifestView(s, vm, onClose = vm::reset)
            is SharedLinkViewModel.State.Failed -> FailedView(s.kind, onRetry = vm::reset)
        }
    }
}

@Composable
private fun LinkInput(onOpen: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    Text(stringResource(R.string.share_open_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.share_open_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.share_open_field)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onOpen(url) }, enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.share_open_action))
    }
}

@Composable
private fun PasswordPrompt(s: SharedLinkViewModel.State.NeedsPassword, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    Text(s.name ?: stringResource(R.string.share_protected), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = pw,
        onValueChange = { pw = it },
        label = { Text(stringResource(R.string.share_password)) },
        singleLine = true,
        isError = s.wrong,
        supportingText = if (s.wrong) ({ Text(stringResource(R.string.share_password_wrong)) }) else null,
        keyboardOptions = secretKeyboardOptions().copy(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (pw.isNotBlank()) onSubmit(pw) }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSubmit(pw) }, enabled = pw.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.share_unlock))
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
}

@Composable
private fun ManifestView(s: SharedLinkViewModel.State.Ready, vm: SharedLinkViewModel, onClose: () -> Unit) {
    val m = s.manifest
    Row2(title = m.name.ifBlank { stringResource(R.string.share_untitled) }, onClose = onClose)
    Text(
        stringResource(R.string.share_file_count, m.files.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FileList(m, vm)
}

@Composable
private fun FileList(m: SharedManifest, vm: SharedLinkViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<SharedFile?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = pending; pending = null
        if (uri != null && f != null) scope.launch {
            val bytes = vm.fileBytes(f) ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
            }
        }
    }
    Column {
        m.files.forEach { f ->
            ListItem(
                headlineContent = { Text(f.name) },
                supportingContent = {
                    val meta = listOf(f.path.takeIf { it.isNotBlank() }, humanSize(f.size)).filterNotNull().joinToString(" · ")
                    Text(meta)
                },
                trailingContent = {
                    OutlinedButton(onClick = { pending = f; saver.launch(f.name) }) {
                        Text(stringResource(R.string.share_save))
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun FailedView(kind: SharedLinkViewModel.ErrorKind, onRetry: () -> Unit) {
    val msg = when (kind) {
        SharedLinkViewModel.ErrorKind.BAD_LINK -> R.string.share_err_bad_link
        SharedLinkViewModel.ErrorKind.WRONG_HOST -> R.string.share_err_wrong_host
        SharedLinkViewModel.ErrorKind.NOT_FOUND -> R.string.share_err_not_found
        SharedLinkViewModel.ErrorKind.EXPIRED -> R.string.share_err_expired
        SharedLinkViewModel.ErrorKind.GENERIC -> R.string.share_err_generic
    }
    Text(stringResource(msg), color = MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.share_open_another)) }
}

@Composable
private fun Row2(title: String, onClose: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onClose) { Text(stringResource(R.string.share_open_another)) }
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
