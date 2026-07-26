package de.ledgerline.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Material 3 tonal schemes for Ledgerline, built from the shared cross-platform
 * brand: an **indigo → violet** accent (byte-aligned with the iOS `Theme.swift`
 * and the web app). The app now ships BOTH a light and a dark scheme; the active
 * one follows the system setting (see [LedgerlineTheme]).
 *
 * The brand seed is the iOS accent `#7066F5` (indigo) with a violet far-stop
 * `#9E70FA`. Each M3 role below is hand-authored from that hue so tonal-container
 * roles (surfaceContainer*, surfaceDim/Bright, inverse*, tertiary*, error*)
 * resolve to intentional indigo-family colors rather than framework fallbacks.
 *
 * The fixed accent gradient used by hero icons, icon chips and primary buttons
 * lives in [Brand] (it is identical in light and dark, matching iOS).
 */

// ============================================================================
//  DARK scheme — the "vault depth" look (indigo on a near-black indigo-tinted
//  surface). Mirrors the iOS dark appearance.
// ============================================================================

// --- Primary (indigo brand) -------------------------------------------------
val DarkPrimary = Color(0xFFC7C0FF)
val DarkOnPrimary = Color(0xFF2A2178)
val DarkPrimaryContainer = Color(0xFF433A90)
val DarkOnPrimaryContainer = Color(0xFFE5DEFF)
val DarkInversePrimary = Color(0xFF5A4FD6)

// --- Secondary (muted indigo-grey) ------------------------------------------
val DarkSecondary = Color(0xFFC8C3DC)
val DarkOnSecondary = Color(0xFF302E42)
val DarkSecondaryContainer = Color(0xFF464459)
val DarkOnSecondaryContainer = Color(0xFFE5DFF9)

// --- Tertiary (violet accent) -----------------------------------------------
val DarkTertiary = Color(0xFFDDB9FF)
val DarkOnTertiary = Color(0xFF44205C)
val DarkTertiaryContainer = Color(0xFF5C3A75)
val DarkOnTertiaryContainer = Color(0xFFF1DBFF)

// --- Error ------------------------------------------------------------------
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

// --- Background / core surface (near-black, faint indigo tint) ---------------
val DarkBackground = Color(0xFF131318)
val DarkOnBackground = Color(0xFFE5E1E9)
val DarkSurface = Color(0xFF131318)
val DarkOnSurface = Color(0xFFE5E1E9)
val DarkSurfaceVariant = Color(0xFF48454F)
val DarkOnSurfaceVariant = Color(0xFFC9C5D0)

// --- Outlines ---------------------------------------------------------------
val DarkOutline = Color(0xFF938F9A)
val DarkOutlineVariant = Color(0xFF48454F)

// --- Inverse / scrim --------------------------------------------------------
val DarkInverseSurface = Color(0xFFE5E1E9)
val DarkInverseOnSurface = Color(0xFF303036)
val DarkScrim = Color(0xFF000000)

// --- Tonal container ramp (surfaceContainer* + dim/bright) ------------------
// A monotonic ladder from the darkest recess up to the brightest raised surface,
// each step tinted slightly indigo so cards/sheets/bars read as one family.
val DarkSurfaceDim = Color(0xFF131318)
val DarkSurfaceBright = Color(0xFF39373E)
val DarkSurfaceContainerLowest = Color(0xFF0E0D12)
val DarkSurfaceContainerLow = Color(0xFF1B1B21)
val DarkSurfaceContainer = Color(0xFF1F1F25)
val DarkSurfaceContainerHigh = Color(0xFF2A292F)
val DarkSurfaceContainerHighest = Color(0xFF35343A)

// ============================================================================
//  LIGHT scheme — indigo accent on a near-white, faintly indigo-tinted surface.
//  Mirrors the iOS light appearance.
// ============================================================================

// --- Primary (indigo brand) -------------------------------------------------
val LightPrimary = Color(0xFF5A4FD6)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE5DEFF)
val LightOnPrimaryContainer = Color(0xFF150764)
val LightInversePrimary = Color(0xFFC7C0FF)

// --- Secondary (muted indigo-grey) ------------------------------------------
val LightSecondary = Color(0xFF5D5C72)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE3E0F9)
val LightOnSecondaryContainer = Color(0xFF1A1A2C)

// --- Tertiary (violet accent) -----------------------------------------------
val LightTertiary = Color(0xFF7A4E9E)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFF1DBFF)
val LightOnTertiaryContainer = Color(0xFF2E0A47)

// --- Error ------------------------------------------------------------------
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

// --- Background / core surface (near-white, faint indigo tint) ---------------
val LightBackground = Color(0xFFFDFBFF)
val LightOnBackground = Color(0xFF1B1B21)
val LightSurface = Color(0xFFFDFBFF)
val LightOnSurface = Color(0xFF1B1B21)
val LightSurfaceVariant = Color(0xFFE5E0EC)
val LightOnSurfaceVariant = Color(0xFF47464F)

// --- Outlines ---------------------------------------------------------------
val LightOutline = Color(0xFF787680)
val LightOutlineVariant = Color(0xFFC9C5D0)

// --- Inverse / scrim --------------------------------------------------------
val LightInverseSurface = Color(0xFF303036)
val LightInverseOnSurface = Color(0xFFF3EFF7)
val LightScrim = Color(0xFF000000)

// --- Tonal container ramp ----------------------------------------------------
val LightSurfaceDim = Color(0xFFDDD9E1)
val LightSurfaceBright = Color(0xFFFDFBFF)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF7F3FB)
val LightSurfaceContainer = Color(0xFFF1EDF6)
val LightSurfaceContainerHigh = Color(0xFFEBE7F0)
val LightSurfaceContainerHighest = Color(0xFFE6E1EB)
