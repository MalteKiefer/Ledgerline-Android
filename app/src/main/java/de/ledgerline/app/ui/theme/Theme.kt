package de.ledgerline.app.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The one and only Ledgerline color scheme: a complete Material 3 dark scheme
 * built from the teal brand. The app is always dark and never uses dynamic
 * (wallpaper) color — this scheme is fully hand-authored in [Color]. Every M3
 * role is filled so components that reach for tonal container roles
 * (surfaceContainer*, surfaceDim/Bright, inverse*, tertiary*, error*) resolve
 * to intentional teal-family colors rather than framework fallbacks.
 */
private val LedgerlineColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    inversePrimary = InversePrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = ErrorColor,
    onError = OnErrorColor,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceTint = SurfaceTint,
    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Scrim,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    surfaceDim = SurfaceDim,
    surfaceBright = SurfaceBright,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
)

/**
 * App-wide Material 3 theme. Emits the full teal [LedgerlineColorScheme], the
 * complete [LedgerlineTypography] scale and the [LedgerlineShapes] corner ramp.
 *
 * Note on Expressive: material3 1.4.0 ships `MaterialExpressiveTheme` but keeps it
 * (and `ExperimentalMaterial3ExpressiveApi`) `internal` — it is not callable from
 * app code in this stable release. We therefore use the standard [MaterialTheme],
 * which is fully expressive-ready: the tonal `surfaceContainer*`/`surfaceDim`/
 * `surfaceBright` roles and the expressive shape/type scales all resolve through it.
 * Switch to `MaterialExpressiveTheme` once it becomes public.
 *
 * The outer [Surface] paints the dark background so every screen has a guaranteed
 * opaque, high-contrast backdrop (fixes text rendering on the raw window
 * background) and so edge-to-edge system bars sit over an intentional color.
 */
@Composable
fun LedgerlineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LedgerlineColorScheme,
        typography = LedgerlineTypography,
        shapes = LedgerlineShapes,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}
