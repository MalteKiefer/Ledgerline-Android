package de.ledgerline.app.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable

/**
 * Standard Material 3 top app bar for the app.
 *
 * Provides a title, an optional back affordation (rendered only when [onBack] is
 * non-null) and an optional trailing [actions] slot. Screens should prefer this
 * over hand-rolling a [TopAppBar] so titles, back buttons and insets stay
 * consistent across the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            when {
                onBack != null -> IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = androidx.compose.ui.res.stringResource(de.ledgerline.app.R.string.action_back),
                    )
                }
                onMenu != null -> IconButton(onClick = onMenu) {
                    Icon(
                        Icons.Outlined.Menu,
                        contentDescription = androidx.compose.ui.res.stringResource(de.ledgerline.app.R.string.menu_more),
                    )
                }
            }
        },
        actions = actions,
    )
}
