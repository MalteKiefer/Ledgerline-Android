package de.ledgerline.app.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private val LedgerlineColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    error = ErrorColor,
    onError = OnErrorColor,
)

/**
 * App-wide Material 3 theme. Wraps content in a [Surface] painted with the dark
 * background so every screen has a guaranteed opaque, high-contrast backdrop
 * (fixes text rendering on the raw window background).
 */
@Composable
fun LedgerlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LedgerlineColorScheme,
        typography = LedgerlineTypography,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}
