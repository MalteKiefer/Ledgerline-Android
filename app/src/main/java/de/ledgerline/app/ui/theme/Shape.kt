package de.ledgerline.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 shape scale for Ledgerline. Radii lean slightly rounder than the
 * M3 baseline to match the Expressive language — softer, friendlier surfaces
 * while keeping the corner ramp monotonic (extraSmall … extraLarge).
 */
val LedgerlineShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
