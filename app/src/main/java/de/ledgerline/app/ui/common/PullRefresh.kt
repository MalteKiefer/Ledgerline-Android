package de.ledgerline.app.ui.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pull-to-refresh wrapper for screens whose refresh is fire-and-forget (the ViewModel's
 * `refresh()`/`load()` returns Unit, not a Job to await). Shows the wavy indicator briefly while
 * [onRefresh] kicks off, so every list screen gets a consistent pull-down gesture. Wrap the
 * scrollable content (a `Column(verticalScroll)` or `LazyColumn`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefresh(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable () -> Unit,
) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            onRefresh()
            scope.launch { delay(700); refreshing = false }
        },
        modifier = modifier,
        content = { content() },
    )
}
