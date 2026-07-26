package de.ledgerline.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.data.ShareOptions
import de.ledgerline.app.ui.theme.PrimaryGradientButton

/** Copy a (non-secret) share link to the clipboard. */
fun copyToClipboard(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm?.setPrimaryClip(android.content.ClipData.newPlainText("share link", text))
}

/** A system share-sheet chooser for a plain-text link (ACTION_SEND). */
fun shareTextChooser(context: android.content.Context, text: String): android.content.Intent {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    return android.content.Intent.createChooser(send, null)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** UI state for the public share-link sheet, owned by the hosting ViewModel. */
data class ShareSheetState(
    val id: String,
    val isFolder: Boolean = false,
    val name: String,
    /** Gallery albums choose download; files always allow it (hide the toggle). */
    val showDownloadToggle: Boolean = false,
    val shared: Boolean = false,
    /** The copyable link (existing share, or one just created); null until created. */
    val link: String? = null,
    val busy: Boolean = false,
    val error: Boolean = false,
)

/**
 * Bottom sheet to create / copy / revoke a public share link. Purely presentational: it
 * collects the options and calls back; the ViewModel owns the crypto + REST via ShareRepository.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLinkSheet(
    state: ShareSheetState,
    onCreate: (ShareOptions) -> Unit,
    onRevoke: () -> Unit,
    onCopy: (String) -> Unit,
    onShareIntent: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var allowDownload by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var expires by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.sharelink_title), style = MaterialTheme.typography.titleLarge)
            Text(state.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (state.link != null) {
                // Already shared (or just created): show the link + copy/share/revoke.
                OutlinedTextField(
                    value = state.link, onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.share_link_label)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCopy(state.link) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.ContentCopy, null); Text(stringResource(R.string.share_copy), Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = { onShareIntent(state.link) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Share, null); Text(stringResource(R.string.share_send), Modifier.padding(start = 6.dp))
                    }
                }
                TextButton(
                    onClick = onRevoke,
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text(stringResource(R.string.share_revoke), color = MaterialTheme.colorScheme.error) }
            } else {
                // Not yet shared: options + create.
                if (state.showDownloadToggle) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.share_allow_download), Modifier.weight(1f))
                        Switch(checked = allowDownload, onCheckedChange = { allowDownload = it })
                    }
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(stringResource(R.string.share_password_optional)) },
                    singleLine = true,
                    keyboardOptions = secretKeyboardOptions(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = expires, onValueChange = { expires = it },
                    label = { Text(stringResource(R.string.share_expires_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (state.error) {
                    Text(
                        stringResource(R.string.share_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    }
                    TextButton(onClick = onDismiss, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) }
                    PrimaryGradientButton(
                        text = stringResource(R.string.share_create),
                        onClick = {
                            onCreate(
                                ShareOptions(
                                    allowDownload = if (state.showDownloadToggle) allowDownload else true,
                                    expiresAtIso = expires.trim().ifBlank { null }?.let { toIsoOrNull(it) },
                                    password = password.ifBlank { null },
                                ),
                            )
                        },
                        enabled = !state.busy,
                    )
                }
            }
        }
    }
}

/**
 * Best-effort convert a `yyyy-MM-dd` (or full ISO) expiry the user typed into an ISO-8601
 * instant at end-of-day UTC. Returns null if it can't be parsed (the share is then unlimited).
 */
private fun toIsoOrNull(input: String): String? = runCatching {
    if (input.contains('T')) {
        java.time.Instant.parse(input).toString()
    } else {
        java.time.LocalDate.parse(input)
            .atTime(23, 59, 59)
            .toInstant(java.time.ZoneOffset.UTC)
            .toString()
    }
}.getOrNull()
