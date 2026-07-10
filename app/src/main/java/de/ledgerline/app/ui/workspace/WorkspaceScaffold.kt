package de.ledgerline.app.ui.workspace

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
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
import de.ledgerline.app.ui.settings.SettingsContent
import de.ledgerline.app.ui.workspace.bookmarks.BookmarksScreen
import de.ledgerline.app.ui.workspace.files.FilesScreen
import de.ledgerline.app.ui.workspace.notes.NotesScreen
import de.ledgerline.app.ui.workspace.todos.TodosScreen

private data class Tab(val labelRes: Int, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScaffold(
    onLockNow: () -> Unit = {},
    onDisconnected: () -> Unit = {},
) {
    val loader: WorkspaceViewModel = hiltViewModel()
    LaunchedEffect(Unit) { loader.ensureLoaded() }

    // Four primary content tabs; secondary destinations (Settings) live in the
    // top-bar overflow menu so the bottom bar never exceeds four items.
    val tabs = listOf(
        Tab(R.string.tab_files, Icons.Outlined.Folder),
        Tab(R.string.tab_notes, Icons.Outlined.Description),
        Tab(R.string.tab_bookmarks, Icons.Outlined.Bookmarks),
        Tab(R.string.tab_todos, Icons.Outlined.CheckCircle),
    )
    var selected by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // Settings is a secondary screen; back exits it to the current tab.
    BackHandler(enabled = showSettings) { showSettings = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (showSettings) R.string.settings_title else tabs[selected].labelRes)) },
                colors = TopAppBarDefaults.topAppBarColors(),
                navigationIcon = {
                    if (showSettings) {
                        IconButton(onClick = { showSettings = false }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (!showSettings) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_title)) },
                                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                                onClick = { menuOpen = false; showSettings = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = !showSettings && selected == i,
                        onClick = { selected = i; showSettings = false },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        if (showSettings) {
            SettingsContent(modifier = m, onLockNow = onLockNow, onDisconnected = onDisconnected)
        } else {
            when (selected) {
                0 -> FilesScreen(m)
                1 -> NotesScreen(m)
                2 -> BookmarksScreen(m)
                else -> TodosScreen(m)
            }
        }
    }
}
