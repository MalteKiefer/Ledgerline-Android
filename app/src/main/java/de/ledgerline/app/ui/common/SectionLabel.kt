package de.ledgerline.app.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import de.ledgerline.app.ui.theme.Brand

/**
 * The app-wide settings/section label: an uppercase, letter-spaced accent caption that sits above a
 * grouped `cardSurface()` card. Shared by Settings, Finance and any other grouped surface so the
 * whole app reads as one system (matches the redesigned Settings home). [danger] tints it red for a
 * destructive group.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, danger: Boolean = false) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = if (danger) MaterialTheme.colorScheme.error else Brand.accent,
        letterSpacing = 0.08.em,
        modifier = modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
    )
}
