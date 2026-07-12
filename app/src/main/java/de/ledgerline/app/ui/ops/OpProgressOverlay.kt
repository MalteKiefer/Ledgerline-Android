package de.ledgerline.app.ui.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.core.ops.OpKind

/**
 * Shared progress overlay driven by [OperationManager.active][de.ledgerline.app.core.ops.OperationManager.active].
 * While any operation is running it dims the screen and shows a spinner plus one
 * `"<kind> current/total"` line per active op (the count is omitted when the total is
 * unknown). Reused across Gallery/People/Duplicates so backgrounded ops keep showing
 * progress when the app returns to the foreground. Renders nothing when idle.
 */
@Composable
fun OpProgressOverlay(modifier: Modifier = Modifier) {
    val vm: OpsViewModel = hiltViewModel()
    val ops by vm.active.collectAsStateWithLifecycle()

    if (ops.isEmpty()) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(color = Color.White)
            ops.forEach { op ->
                val label = stringResource(kindLabel(op.kind))
                val line = if (op.total > 0) "$label ${op.current}/${op.total}" else label
                Text(
                    text = line,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun kindLabel(kind: OpKind): Int = when (kind) {
    OpKind.FACE_SCAN -> R.string.ops_kind_face_scan
    OpKind.DUPLICATE_SCAN -> R.string.ops_kind_duplicate_scan
    OpKind.UPLOAD -> R.string.ops_kind_upload
    OpKind.BLOB_CLEANUP -> R.string.ops_kind_blob_cleanup
    OpKind.PREFETCH -> R.string.ops_kind_prefetch
    OpKind.BACKUP -> R.string.ops_kind_backup
}
