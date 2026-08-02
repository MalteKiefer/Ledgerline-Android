package de.ledgerline.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable

/**
 * Colors for a **read-only picker field** built as a disabled `OutlinedTextField` (so a tap overlay
 * can open a dropdown). Without this the M3 *disabled* palette greys the text/label/border and the
 * field reads as inactive ("washed out"). Here the disabled tones are mapped back to the normal
 * enabled ones so a picker looks tappable, not disabled — while still swallowing keyboard focus.
 */
@Composable
fun pickerFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    disabledTextColor = MaterialTheme.colorScheme.onSurface,
    disabledBorderColor = MaterialTheme.colorScheme.outline,
    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
