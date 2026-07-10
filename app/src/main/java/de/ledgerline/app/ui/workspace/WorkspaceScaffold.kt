package de.ledgerline.app.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import de.ledgerline.app.ui.gallery.GalleryScreen
import de.ledgerline.app.ui.settings.SettingsContent
import de.ledgerline.app.ui.workspace.bookmarks.BookmarksScreen
import de.ledgerline.app.ui.workspace.files.FilesScreen
import de.ledgerline.app.ui.workspace.notes.NotesScreen
import de.ledgerline.app.ui.workspace.todos.TodosScreen

private data class Tab(val labelRes: Int, val icon: ImageVector)

/** Secondary destinations reached from the bottom-bar "More" sheet. */
private enum class Overflow { Bookmarks, Settings }

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
        Tab(R.string.tab_files, Icons.Outlined.Folder),
        Tab(R.string.tab_gallery, Icons.Outlined.PhotoLibrary),
        Tab(R.string.tab_todos, Icons.Outlined.CheckCircle),
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

    Scaffold(
        topBar = {
            if (!chromeHidden) {
                TopAppBar(
                    title = { Text(stringResource(tabs[selected].labelRes)) },
                    colors = TopAppBarDefaults.topAppBarColors(),
                )
            }
        },
        bottomBar = {
            if (!chromeHidden) {
                NavigationBar {
                    tabs.forEachIndexed { i, tab ->
                        NavigationBarItem(
                            selected = selected == i,
                            onClick = { overflow = null; selected = i },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                    NavigationBarItem(
                        selected = false,
                        onClick = { showSheet = true },
                        icon = { Icon(Icons.Outlined.MoreVert, contentDescription = null) },
                        label = { Text(stringResource(R.string.menu_more)) },
                    )
                }
            }
        },
        // When a full-screen view is shown, the outer scaffold must NOT consume the
        // status-bar inset — the inner view's own TopAppBar handles it. This removes
        // the double inset / gap.
        contentWindowInsets = if (chromeHidden) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
    ) { innerPadding ->
        CompositionLocalProvider(LocalFullscreen provides fullscreen) {
            when (overflow) {
                Overflow.Bookmarks -> Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.menu_bookmarks)) },
                            navigationIcon = {
                                IconButton(onClick = { overflow = null }) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = stringResource(R.string.action_back),
                                    )
                                }
                            },
                        )
                    },
                ) { p -> BookmarksScreen(Modifier.padding(p)) }

                Overflow.Settings -> Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.settings_title)) },
                            navigationIcon = {
                                IconButton(onClick = { overflow = null }) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = stringResource(R.string.action_back),
                                    )
                                }
                            },
                        )
                    },
                ) { p ->
                    SettingsContent(
                        modifier = Modifier.padding(p),
                        onLockNow = onLockNow,
                        onDisconnected = onDisconnected,
                    )
                }

                null -> {
                    val m = Modifier.padding(innerPadding)
                    when (selected) {
                        0 -> FilesScreen(m)
                        1 -> GalleryScreen(m)
                        2 -> TodosScreen(m)
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
                    .clickable { overflow = Overflow.Bookmarks; showSheet = false },
                leadingContent = { Icon(Icons.Outlined.Bookmarks, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.menu_bookmarks)) },
            )
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { overflow = Overflow.Settings; showSheet = false },
                leadingContent = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.settings_title)) },
            )
        }
    }
}
