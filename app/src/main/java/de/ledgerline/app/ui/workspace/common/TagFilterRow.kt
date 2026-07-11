package de.ledgerline.app.ui.workspace.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R

/**
 * A compact, horizontally-scrollable row of tag filter chips: an "All tags" chip
 * (clears the filter) followed by one [FilterChip] per tag. Renders nothing when
 * [tags] is empty.
 *
 * Must live in a fixed header (never inside a LazyColumn) so it is measured with a
 * bounded height. Selecting a chip calls [onSelect] with the tag, or null for "All".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagFilterRow(
    tags: List<String>,
    activeTag: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = activeTag == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.filter_all_tags)) },
        )
        tags.forEach { tag ->
            FilterChip(
                selected = activeTag == tag,
                onClick = { onSelect(tag) },
                label = { Text(tag) },
            )
        }
    }
}
