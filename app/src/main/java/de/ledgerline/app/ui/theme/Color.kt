package de.ledgerline.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Full Material 3 dark tonal scheme derived from the Ledgerline teal brand.
 *
 * The app is ALWAYS dark and privacy-first, so this is the single source of
 * truth — there is no light variant and no dynamic (wallpaper) color. Every M3
 * color role is defined here from a coherent teal ramp (primary), a muted
 * teal-grey (secondary), a complementary warm amber (tertiary), and a neutral
 * surface ramp tinted very slightly toward teal so containers read as a family
 * rather than flat grey.
 *
 * Naming follows the M3 role names 1:1 so `darkColorScheme(...)` in Theme.kt is
 * a direct, exhaustive mapping.
 */

// --- Primary (teal brand) ---------------------------------------------------
val Primary = Color(0xFF4FD8C4)
val OnPrimary = Color(0xFF00382F)
val PrimaryContainer = Color(0xFF005046)
val OnPrimaryContainer = Color(0xFF6FF7E3)
val InversePrimary = Color(0xFF006A5C)

// --- Secondary (muted teal-grey) --------------------------------------------
val Secondary = Color(0xFFB0CCC5)
val OnSecondary = Color(0xFF1B3530)
val SecondaryContainer = Color(0xFF324B45)
val OnSecondaryContainer = Color(0xFFCCE8E1)

// --- Tertiary (complementary warm amber, for accents/highlights) ------------
val Tertiary = Color(0xFFF3C06B)
val OnTertiary = Color(0xFF422C00)
val TertiaryContainer = Color(0xFF5E4100)
val OnTertiaryContainer = Color(0xFFFFDEA6)

// --- Error ------------------------------------------------------------------
val ErrorColor = Color(0xFFFFB4AB)
val OnErrorColor = Color(0xFF690005)
val ErrorContainer = Color(0xFF93000A)
val OnErrorContainer = Color(0xFFFFDAD6)

// --- Background / core surface ----------------------------------------------
val Background = Color(0xFF0E1513)
val OnBackground = Color(0xFFDEE4E1)
val Surface = Color(0xFF0E1513)
val OnSurface = Color(0xFFDEE4E1)
val SurfaceVariant = Color(0xFF1B2723)
val OnSurfaceVariant = Color(0xFFBFCBC6)
val SurfaceTint = Primary

// --- Outlines ---------------------------------------------------------------
val Outline = Color(0xFF89938F)
val OutlineVariant = Color(0xFF3F4945)

// --- Inverse / scrim --------------------------------------------------------
val InverseSurface = Color(0xFFDEE4E1)
val InverseOnSurface = Color(0xFF2B322F)
val Scrim = Color(0xFF000000)

// --- Tonal container ramp (M3 surfaceContainer* + dim/bright) ---------------
// A deliberate, monotonic ladder from the darkest recess (surfaceDim /
// containerLowest) up to the brightest raised surface (surfaceBright /
// containerHighest). Each step is tinted slightly teal so elevated cards,
// sheets and bars read as one coherent family, never a single flat grey.
val SurfaceDim = Color(0xFF0E1513)
val SurfaceBright = Color(0xFF343B38)
val SurfaceContainerLowest = Color(0xFF090F0D)
val SurfaceContainerLow = Color(0xFF161D1B)
val SurfaceContainer = Color(0xFF1A211F)
val SurfaceContainerHigh = Color(0xFF242B29)
val SurfaceContainerHighest = Color(0xFF2F3634)
