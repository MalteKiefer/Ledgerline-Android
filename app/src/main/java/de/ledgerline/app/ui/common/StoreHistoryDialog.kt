package de.ledgerline.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.data.remote.dto.StoreHistoryEntry
import kotlinx.coroutines.launch

/**
 * A self-contained "version history / recovery" dialog: lists the store's retained sealed-root
 * versions (recovery net, v1.536) with date + size, and on tap recovers records missing from the
 * current store from that version. [load] fetches the version list; [recover] restores a version and
 * returns the count restored (or -1 on failure).
 */
@Composable
fun StoreHistoryDialog(
    onDismiss: () -> Unit,
    load: suspend () -> List<StoreHistoryEntry>,
    recover: suspend (Int) -> Int,
) {
    val scope = rememberCoroutineScope()
    var versions by remember { mutableStateOf<List<StoreHistoryEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val failMsg = stringResource(R.string.history_recover_failed)
    val noneMsg = stringResource(R.string.history_recover_none)
    val okPrefix = stringResource(R.string.history_recover_ok)
    LaunchedEffect(Unit) { versions = load() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(R.string.history_title)) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp)) }
                val v = versions
                when {
                    v == null -> Text(stringResource(R.string.history_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    v.isEmpty() -> Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> v.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                busy = true; message = null
                                scope.launch {
                                    val n = recover(e.version)
                                    busy = false
                                    message = when {
                                        n < 0 -> failMsg
                                        n == 0 -> noneMsg
                                        else -> "$okPrefix $n"
                                    }
                                }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("v${e.version}", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(e.created_at?.take(10), "${e.bytes / 1024} KB").joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(stringResource(R.string.history_restore), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
    )
}
