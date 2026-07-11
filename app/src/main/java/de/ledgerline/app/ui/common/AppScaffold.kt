package de.ledgerline.app.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Reusable edge-to-edge scaffold wrapper — the single correct window-insets model
 * for the app.
 *
 * The activity draws edge-to-edge (transparent system bars), so content must be
 * inset from the status and navigation bars to avoid being drawn under them, while
 * NOT introducing a phantom top gap. This wrapper delegates to the M3 [Scaffold],
 * which already applies the correct system-bar insets to [content] via the padding
 * it hands back — nothing more is needed for the common case.
 *
 * For full-bleed "immersive" screens (photo viewer, camera preview) that
 * intentionally draw behind the system bars and manage their own insets on their
 * own controls (via `statusBarsPadding()` / `navigationBarsPadding()`), pass
 * [immersive] = true: the scaffold then consumes NO insets and hands back zero
 * padding, giving the caller the whole screen.
 *
 * @param immersive when true, draw full-bleed (zero content insets); the caller
 *   owns its own status/navigation-bar padding.
 * @param topBar optional top app bar slot.
 * @param bottomBar optional bottom bar slot.
 * @param content receives [PaddingValues] that already account for the top/bottom
 *   bars and (unless [immersive]) the system bars.
 */
@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    immersive: Boolean = false,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        // Immersive screens own their insets; the common case gets the standard
        // safe-area insets so content never hides under the system bars and there
        // is no double-inset gap.
        contentWindowInsets = if (immersive) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        content = content,
    )
}
