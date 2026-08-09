package de.ledgerline.app.ui.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Standard swipe-down-to-refresh wrapper used across the app. Wrap a scrollable content area
 * (LazyColumn / verticalScroll) so the user can pull to reload from the server. [refreshing] drives
 * the spinner; [onRefresh] triggers the reload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        content()
    }
}
