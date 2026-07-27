package de.ledgerline.app.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import de.ledgerline.app.R
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.gallery.GalleryScreen
import de.ledgerline.app.ui.settings.SettingsContent
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.IconChip
import de.ledgerline.app.ui.workspace.bookmarks.BookmarksScreen
import de.ledgerline.app.ui.workspace.contacts.ContactsScreen
import de.ledgerline.app.ui.workspace.files.FilesScreen
import de.ledgerline.app.ui.workspace.notes.NotesScreen
import de.ledgerline.app.ui.workspace.todos.TodosScreen

private data class Tab(val labelRes: Int, val icon: ImageVector)

/** Secondary destinations reached from the navigation "More" sheet. */
private enum class Overflow { Todos, Bookmarks, Contacts, Settings }

/**
 * Adaptive workspace shell (Material 3 Expressive). Primary destinations live in a
 * [NavigationSuiteScaffold] that renders as a bottom bar on compact width, a navigation rail
 * on medium, and a navigation drawer on expanded — automatically from the window size class.
 * A nested full-screen view (viewer/detail) collapses the navigation to [NavigationSuiteType.None].
 * Secondary destinations (Todos/Bookmarks/Contacts/Settings) open full-screen from the "More" sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScaffold(
    onLockNow: () -> Unit = {},
    onDisconnected: () -> Unit = {},
) {
    val loader: WorkspaceViewModel = hiltViewModel()
    LaunchedEffect(Unit) { loader.ensureLoaded() }

    val tabs = listOf(
        Tab(R.string.tab_passwords, Icons.Outlined.Password),
        Tab(R.string.tab_files, Icons.Outlined.Folder),
        Tab(R.string.tab_gallery, Icons.Outlined.PhotoLibrary),
        Tab(R.string.tab_notes, Icons.Outlined.Description),
    )
    var selected by remember { mutableIntStateOf(0) }
    var overflow by remember { mutableStateOf<Overflow?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    // Nested detail/viewer screens toggle this to claim the whole screen.
    val fullscreen = remember { mutableStateOf(false) }

    // Back exits an overflow destination to the current tab.
    BackHandler(enabled = overflow != null) { overflow = null }

    val sheetState = rememberModalBottomSheetState()

    CompositionLocalProvider(LocalFullscreen provides fullscreen) {
        if (overflow != null) {
            // Secondary destination: it owns the whole screen (its own scaffolds/insets).
            when (overflow) {
                Overflow.Bookmarks -> AppScaffold(
                    immersive = fullscreen.value,
                    topBar = { if (!fullscreen.value) AppTopBar(stringResource(R.string.menu_bookmarks), onBack = { overflow = null }) },
                ) { p -> BookmarksScreen(Modifier.padding(p)) }

                Overflow.Todos -> AppScaffold(
                    topBar = { AppTopBar(stringResource(R.string.tab_todos), onBack = { overflow = null }) },
                ) { p -> TodosScreen(Modifier.padding(p)) }

                Overflow.Contacts -> ContactsScreen(onExit = { overflow = null })

                Overflow.Settings -> SettingsContent(
                    onLockNow = onLockNow,
                    onDisconnected = onDisconnected,
                    onBack = { overflow = null },
                )

                null -> Unit
            }
        } else {
            // Primary tabs in the adaptive navigation suite. Collapse the nav when a nested
            // full-screen view (viewer/detail) is showing, so it owns the whole surface.
            val suiteType = if (fullscreen.value) {
                NavigationSuiteType.None
            } else {
                NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())
            }
            NavigationSuiteScaffold(
                layoutType = suiteType,
                navigationSuiteItems = {
                    tabs.forEachIndexed { i, tab ->
                        item(
                            selected = selected == i,
                            onClick = { selected = i },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                    item(
                        selected = false,
                        onClick = { showSheet = true },
                        icon = { Icon(Icons.Outlined.MoreHoriz, contentDescription = null) },
                        label = { Text(stringResource(R.string.menu_more)) },
                    )
                },
            ) {
                // Top inset handled here (no top app bar — each tab owns its compact header).
                AppScaffold(immersive = fullscreen.value, topBar = {}) { innerPadding ->
                    val m = Modifier.padding(innerPadding)
                    // Material fade-through between tabs.
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            (fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90)))
                        },
                        label = "tab",
                    ) { sel ->
                        when (sel) {
                            0 -> de.ledgerline.app.ui.passwords.PasswordsScreen(m)
                            1 -> FilesScreen(m)
                            2 -> GalleryScreen(m)
                            else -> NotesScreen(m)
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { overflow = Overflow.Todos; showSheet = false },
                leadingContent = { IconChip(Icons.Outlined.CheckCircle, tint = Brand.tintGreen) },
                headlineContent = { Text(stringResource(R.string.tab_todos)) },
            )
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { overflow = Overflow.Bookmarks; showSheet = false },
                leadingContent = { IconChip(Icons.Outlined.Bookmarks, tint = Brand.tintOrange) },
                headlineContent = { Text(stringResource(R.string.menu_bookmarks)) },
            )
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { overflow = Overflow.Contacts; showSheet = false },
                leadingContent = { IconChip(Icons.Outlined.Contacts, tint = Brand.tintBlue) },
                headlineContent = { Text(stringResource(R.string.menu_contacts)) },
            )
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { overflow = Overflow.Settings; showSheet = false },
                leadingContent = { IconChip(Icons.Outlined.Settings, tint = Brand.tintGray) },
                headlineContent = { Text(stringResource(R.string.settings_title)) },
            )
        }
    }
}
