package de.ledgerline.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

@Composable
fun LedgerlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
