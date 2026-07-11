package de.ledgerline.app.ui.workspace.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R

/**
 * Header shown while a workspace screen is in trash view: a back-to-list button,
 * a "Trash" title, and an "Empty trash" action. Rendered above the list (never as a
 * LazyColumn item), consistent with the other fixed toolbars.
 */
@Composable
fun TrashBar(
    onBack: () -> Unit,
    onEmptyTrash: () -> Unit,
    emptyEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back))
            }
            Text(
                stringResource(R.string.trash_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onEmptyTrash, enabled = emptyEnabled) {
                Icon(
                    Icons.Outlined.DeleteSweep,
                    stringResource(R.string.trash_empty),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
