package de.ledgerline.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The Ledgerline dark scheme: a complete Material 3 dark scheme built from the
 * indigo → violet brand ([Color]). Never uses dynamic (wallpaper) color — the
 * palette is hand-authored so every M3 role is intentional.
 */
private val LedgerlineDarkScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = DarkInversePrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = DarkScrim,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
)

/** The Ledgerline light scheme — indigo accent on a near-white indigo-tinted surface. */
private val LedgerlineLightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = LightInversePrimary,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = LightScrim,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
)

/**
 * App-wide Material 3 theme. Selects the light or dark [MaterialTheme] scheme by
 * the system setting ([isSystemInDarkTheme]) — matching the adaptive iOS app —
 * and emits the shared [LedgerlineTypography] scale and [LedgerlineShapes] ramp.
 *
 * The outer [Surface] paints the scheme background so every screen has a
 * guaranteed opaque backdrop and edge-to-edge system bars sit over an
 * intentional color.
 */
@Composable
fun LedgerlineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Ledgerline is a strongly-branded ZK app: the indigo/violet identity (iOS/web parity) is the
    // whole design system, so the palette is ALWAYS the hand-authored brand scheme. Material-You /
    // dynamic (wallpaper) color is deliberately NOT used — a wallpaper's hue (e.g. a green photo)
    // recolours only the M3-default components (FABs, chips, buttons, loading, drawer selection)
    // while the hard-branded pills/gradients stay indigo, which reads as a broken two-accent app.
    val scheme = if (darkTheme) LedgerlineDarkScheme else LedgerlineLightScheme
    // Material 3 Expressive: opt into the expressive motion scheme (springier spatial +
    // effects tokens) so every M3 component animates to the current Android standard.
    @OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
    androidx.compose.material3.MaterialExpressiveTheme(
        colorScheme = scheme,
        typography = LedgerlineTypography,
        shapes = LedgerlineShapes,
        motionScheme = androidx.compose.material3.MotionScheme.expressive(),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}
