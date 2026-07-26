package de.ledgerline.app.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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

/** Secondary destinations reached from the bottom-bar "More" sheet. */
private enum class Overflow { Todos, Bookmarks, Contacts, Settings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScaffold(
    onLockNow: () -> Unit = {},
    onDisconnected: () -> Unit = {},
) {
    val loader: WorkspaceViewModel = hiltViewModel()
    LaunchedEffect(Unit) { loader.ensureLoaded() }

    // Four primary content tabs; secondary destinations (Bookmarks, Settings) live
    // behind the bottom-bar "More" item, shown in a modal bottom sheet.
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

    // Hide the outer chrome whenever a nested full-screen view is composed, OR an
    // overflow destination (its own full-screen Scaffold) is showing.
    val chromeHidden = fullscreen.value || overflow != null

    // Back exits an overflow destination to the current tab.
    BackHandler(enabled = overflow != null) { overflow = null }

    val sheetState = rememberModalBottomSheetState()

    // The outer scaffold hosts the primary tabs. When a nested full-screen view is
    // composed (chromeHidden) it drops its own bars AND stops consuming the system-bar
    // insets (immersive = true) — the nested view owns its top bar/insets. This is the
    // single edge-to-edge model: no phantom top gap, content never clipped.
    AppScaffold(
        immersive = chromeHidden,
        // No top title bar: the bottom navigation already labels the section, and each
        // tab owns its own compact header. This reclaims a full app-bar of vertical
        // space (the gallery in particular was losing ~a third of the screen to chrome).
        topBar = {},
        bottomBar = {
            if (!chromeHidden) {
                NavigationBar {
                    tabs.forEachIndexed { i, tab ->
                        NavigationBarItem(
                            selected = selected == i,
                            onClick = { overflow = null; selected = i },
                            icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                    NavigationBarItem(
                        selected = false,
                        onClick = { showSheet = true },
                        icon = { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.menu_more)) },
                        label = { Text(stringResource(R.string.menu_more)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        CompositionLocalProvider(LocalFullscreen provides fullscreen) {
            when (overflow) {
                // When Bookmarks opens a full-screen detail (it sets LocalFullscreen), drop
                // this wrapper's bar + insets so the detail owns the screen — otherwise the
                // detail's own top bar stacks under this one (double chrome).
                Overflow.Bookmarks -> AppScaffold(
                    immersive = fullscreen.value,
                    topBar = { if (!fullscreen.value) AppTopBar(stringResource(R.string.menu_bookmarks), onBack = { overflow = null }) },
                ) { p -> BookmarksScreen(Modifier.padding(p)) }

                // Contacts owns its own top bar (so the full-screen contact detail can
                // replace it cleanly — no double back arrow). onExit closes the overflow.
                Overflow.Todos -> AppScaffold(
                    topBar = { AppTopBar(stringResource(R.string.tab_todos), onBack = { overflow = null }) },
                ) { p -> TodosScreen(Modifier.padding(p)) }

                Overflow.Contacts -> ContactsScreen(onExit = { overflow = null })

                Overflow.Settings -> SettingsContent(
                    onLockNow = onLockNow,
                    onDisconnected = onDisconnected,
                    onBack = { overflow = null },
                )

                null -> {
                    val m = Modifier.padding(innerPadding)
                    when (selected) {
                        0 -> de.ledgerline.app.ui.passwords.PasswordsScreen(m)
                        1 -> FilesScreen(m)
                        2 -> GalleryScreen(m)
                        else -> NotesScreen(m)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { overflow = Overflow.Todos; showSheet = false },
                leadingContent = { IconChip(Icons.Outlined.CheckCircle, tint = Brand.tintGreen) },
                headlineContent = { Text(stringResource(R.string.tab_todos)) },
            )
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { overflow = Overflow.Bookmarks; showSheet = false },
                leadingContent = { IconChip(Icons.Outlined.Bookmarks, tint = Brand.tintOrange) },
                headlineContent = { Text(stringResource(R.string.menu_bookmarks)) },
            )
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { overflow = Overflow.Contacts; showSheet = false },
                leadingContent = { IconChip(Icons.Outlined.Contacts, tint = Brand.tintBlue) },
                headlineContent = { Text(stringResource(R.string.menu_contacts)) },
            )
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { overflow = Overflow.Settings; showSheet = false },
                leadingContent = { IconChip(Icons.Outlined.Settings, tint = Brand.tintGray) },
                headlineContent = { Text(stringResource(R.string.settings_title)) },
            )
        }
    }
}
