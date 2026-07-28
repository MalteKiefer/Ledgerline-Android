package de.ledgerline.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.ledgerline.app.ui.theme.Brand

/**
 * The shared "grouped inset list" design language (see the approved screen mockup):
 * a small uppercase [SectionLabel], a rounded [ListSectionCard] holding [LedgerRow]s
 * separated by start-indented hairlines, and a soft tinted [SoftIconChip] leading each
 * row. New list UI across the app (Files, Vault, Notes, …) is built from these so every
 * surface reads the same — dense, elegant, sparse, one accent moment elsewhere.
 */

private val GroupRadius = 22.dp
private val RowHPad = 14.dp
private val ChipSize = 38.dp
/** Where the hairline divider starts, so it clears the leading chip. */
private val DividerIndent = RowHPad + ChipSize + 13.dp

/** Small uppercase, tracked, faint section header above a [ListSectionCard]. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.09.em),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 8.dp),
    )
}

/**
 * The rounded card that groups a run of rows. Sits on the screen ground with a slightly
 * raised container colour (no hard border — the tonal contrast carries it), inset
 * horizontally so the ground breathes around it.
 */
@Composable
fun ListSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(GroupRadius),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column { content() }
    }
}

/**
 * Convenience: a [ListSectionCard] over [items], drawing a start-indented hairline between
 * consecutive rows automatically. [row] renders one item. NON-LAZY — use only for small,
 * fixed groups (a handful of rows). For variable-length lists use [listSection] so the
 * rows stay virtualized (rendering a large list as one item blocks the main thread → ANR).
 */
@Composable
fun <T> ListSection(
    items: List<T>,
    modifier: Modifier = Modifier,
    row: @Composable (T) -> Unit,
) {
    ListSectionCard(modifier) {
        items.forEachIndexed { i, item ->
            if (i > 0) RowDivider()
            row(item)
        }
    }
}

/** Where a row sits within its rounded group (drives corner rounding + top divider). */
enum class GroupPos { SINGLE, FIRST, MIDDLE, LAST }

private fun groupPosOf(index: Int, count: Int): GroupPos = when {
    count <= 1 -> GroupPos.SINGLE
    index == 0 -> GroupPos.FIRST
    index == count - 1 -> GroupPos.LAST
    else -> GroupPos.MIDDLE
}

/**
 * Lazy grouped section: emits one [LazyListScope] item per element so long lists stay
 * virtualized, while the rounded-card grouping + hairlines are reproduced by shaping each
 * row segment ([GroupContainer]). Drop-in for `items(...)` that yields the grouped look.
 */
fun <T> LazyListScope.listSection(
    items: List<T>,
    key: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    itemsIndexed(items, key = { _, it -> key(it) }) { index, item ->
        GroupContainer(groupPosOf(index, items.size)) { row(item) }
    }
}

/** One row segment of a lazy grouped section: rounds only its outer corners and draws the
 *  top hairline for non-first rows, so stacked segments read as a single rounded card. */
@Composable
fun GroupContainer(pos: GroupPos, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val top = pos == GroupPos.FIRST || pos == GroupPos.SINGLE
    val bottom = pos == GroupPos.LAST || pos == GroupPos.SINGLE
    val shape = RoundedCornerShape(
        topStart = if (top) GroupRadius else 0.dp,
        topEnd = if (top) GroupRadius else 0.dp,
        bottomStart = if (bottom) GroupRadius else 0.dp,
        bottomEnd = if (bottom) GroupRadius else 0.dp,
    )
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = if (top) 6.dp else 0.dp, bottom = if (bottom) 6.dp else 0.dp),
    ) {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                if (pos == GroupPos.MIDDLE || pos == GroupPos.LAST) RowDivider()
                content()
            }
        }
    }
}

/** A start-indented hairline between two rows inside a [ListSectionCard]. */
@Composable
fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = DividerIndent)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

/**
 * A dense list row: soft tinted [leading] chip, [title] (+ optional [subtitle]), and a
 * [trailing] slot (meta text / star / chevron). ~52dp tall.
 */
@Composable
fun LedgerRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = RowHPad, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.size(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.size(8.dp))
            trailing()
        }
    }
}

/**
 * Soft tinted icon chip for grouped-list rows: [tint] at low opacity behind a [tint]-coloured
 * glyph (vs [de.ledgerline.app.ui.theme.IconChip], a solid gradient with a white glyph).
 * Pass [tint] = null for the brand accent.
 */
@Composable
fun SoftIconChip(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    contentDescription: String? = null,
) {
    val color = tint ?: Brand.accent
    Box(
        modifier = modifier
            .size(ChipSize)
            .clip(RoundedCornerShape(Brand.chipRadius))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = color, modifier = Modifier.size(ChipSize * 0.52f))
    }
}

/** Trailing chevron for rows that drill in. */
@Composable
fun RowChevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = LocalContentColor.current.copy(alpha = 0.4f),
        modifier = Modifier.size(20.dp),
    )
}

/** Trailing meta text (size, count, "2FA") — tabular, faint. */
@Composable
fun RowMeta(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
    )
}

/** Standard bottom content padding for a list so its last rows clear the floating tab pill. */
val ListBottomPadding = PaddingValues(bottom = 104.dp)
