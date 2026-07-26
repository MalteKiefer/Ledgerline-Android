package de.ledgerline.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Cross-platform brand tokens + the shared design-system building blocks, ported
 * byte-for-byte from the iOS app (`App/DesignSystem/Theme.swift` +
 * `Components.swift`): an indigo → violet accent, gradient hero icons, tinted
 * icon chips, rounded card surfaces and a gradient primary button.
 *
 * New UI should reach for these ([IconChip], [HeroIcon], [Modifier.cardSurface],
 * [PrimaryGradientButton], [SecondaryBrandButton]) so every screen reads as one
 * system, matching the iOS client and the web app.
 */
object Brand {
    // --- Accent (fixed in light & dark, exactly as iOS) ---------------------
    /** Primary accent — indigo (iOS `Theme.accent`, #7066F5). */
    val accent = Color(0xFF7066F5)
    /** Secondary accent — violet, the far stop of the accent gradient (#9E70FA). */
    val accentSoft = Color(0xFF9E70FA)

    // --- Category tints for grouped-list icon chips (byte-aligned with web/iOS) ---
    val tintBlue = Color(0xFF3B9FD6)
    val tintGreen = Color(0xFF59AD6B)
    val tintOrange = Color(0xFFE2915A)
    val tintTeal = Color(0xFF3FAE9F)
    val tintViolet = Color(0xFF9E70FA)
    val tintGray = Color(0xFF6B7280)

    /** Diagonal accent gradient (top-left → bottom-right) for hero icons, chips, buttons. */
    val accentGradient = Brush.linearGradient(
        colors = listOf(accent, accentSoft),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )

    // --- Metrics (match iOS Theme) ------------------------------------------
    val cardRadius: Dp = 18.dp
    val chipRadius: Dp = 12.dp
    val chipSize: Dp = 38.dp
    val cardPadding: Dp = 16.dp
    val screenPadding: Dp = 20.dp
}

// ============================================================================
//  Icon chip — a rounded-square tile with a white symbol, gradient or tinted.
// ============================================================================

/**
 * A rounded-square icon tile with a white [icon]. Filled with the accent
 * gradient by default, or a solid [tint]. Port of the iOS `IconChip`.
 */
@Composable
fun IconChip(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = Brand.chipSize,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(Brand.chipRadius))
            .then(
                if (tint != null) Modifier.background(tint)
                else Modifier.background(Brand.accentGradient),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

// ============================================================================
//  Hero icon — a large gradient circle with a centered symbol (auth screens).
// ============================================================================

/** A 96dp gradient circle with a centered white [icon]. Port of the iOS `HeroIcon`. */
@Composable
fun HeroIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 50))
            .background(Brand.accentGradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // decorative; the screen title conveys meaning
            tint = Color.White,
            modifier = Modifier.size(size * 0.44f),
        )
    }
}

// ============================================================================
//  Card surface — a rounded container with a hairline stroke.
// ============================================================================

/**
 * Wrap a view in the standard card surface: rounded [Brand.cardRadius] corners,
 * a `surfaceContainer` fill, a hairline outline stroke and inner padding. Port of
 * the iOS `.cardSurface()`.
 */
@Composable
fun Modifier.cardSurface(
    shape: Shape = RoundedCornerShape(Brand.cardRadius),
    padded: Boolean = true,
): Modifier {
    val fill = MaterialTheme.colorScheme.surfaceContainer
    val stroke = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    return this
        .clip(shape)
        .background(fill, shape)
        .border(BorderStroke(1.dp, stroke), shape)
        .then(if (padded) Modifier.padding(Brand.cardPadding) else Modifier)
}

// ============================================================================
//  Buttons — full-width gradient primary + tonal secondary.
// ============================================================================

/** Full-width accent-gradient primary button. Port of the iOS `PrimaryButtonStyle`. */
@Composable
fun PrimaryGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.accentGradient),
        shape = RoundedCornerShape(14.dp),
        // transparent so the gradient background shows through
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.White,
        ),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** Full-width tonal secondary button with an accent-tinted outline. */
@Composable
fun SecondaryBrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

// ============================================================================
//  App background — the scheme surface with two faint accent glows.
// ============================================================================

/**
 * A subtle, adaptive backdrop: the scheme background with two faint radial accent
 * glows (top-left indigo, bottom-right violet), giving the "vault" depth in dark
 * mode and a soft tint in light. Port of the iOS `AppBackground`. Place behind a
 * screen's content in a [Box].
 */
@Composable
fun LedgerlineBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val base = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Brand.accent.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.10f),
                        radius = size.width * 0.9f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Brand.accentSoft.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.90f, size.height * 0.85f),
                        radius = size.width * 0.8f,
                    ),
                )
            },
        content = { content() },
    )
}

/** No-op shape marker kept for call-site clarity where a rectangle fill is wanted. */
val NoShape: Shape = RectangleShape
