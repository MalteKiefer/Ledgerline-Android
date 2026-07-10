package de.ledgerline.app.ui.workspace.todos

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.ledgerline.app.R
import java.util.Locale

/** Maps a raw todo priority value to a localized label; unknown values are capitalized as-is. */
@Composable
fun priorityLabel(priority: String): String = when (priority.lowercase(Locale.ROOT)) {
    "low" -> stringResource(R.string.priority_low)
    "normal" -> stringResource(R.string.priority_normal)
    "high" -> stringResource(R.string.priority_high)
    "urgent" -> stringResource(R.string.priority_urgent)
    else -> priority.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
