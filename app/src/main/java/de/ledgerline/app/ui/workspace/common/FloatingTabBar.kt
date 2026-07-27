package de.ledgerline.app.ui.workspace.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.ledgerline.app.ui.theme.Brand

/**
 * A floating pill toolbar that switches a screen's sub-tabs (e.g. Photos/Albums/People),
 * meant to sit at the bottom-center of a screen — the drawer handles between-section nav, this
 * handles within-section nav. Selected tab fills with the brand gradient. Reserve ~92dp of
 * bottom content padding so list content clears it.
 */
@Composable
fun FloatingTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.navigationBarsPadding().padding(bottom = 14.dp),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            tabs.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Transparent,
                    onClick = { onSelect(i) },
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .then(if (selected) Modifier.background(Brand.accentGradient, RoundedCornerShape(999.dp)) else Modifier)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
