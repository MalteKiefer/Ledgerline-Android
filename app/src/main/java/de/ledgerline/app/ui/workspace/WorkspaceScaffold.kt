package de.ledgerline.app.ui.workspace

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import de.ledgerline.app.R
import de.ledgerline.app.ui.workspace.bookmarks.BookmarksScreen
import de.ledgerline.app.ui.workspace.files.FilesScreen
import de.ledgerline.app.ui.workspace.notes.NotesScreen
import de.ledgerline.app.ui.workspace.todos.TodosScreen

private data class Tab(val labelRes: Int, val icon: ImageVector)

@Composable
fun WorkspaceScaffold() {
    val tabs = listOf(
        Tab(R.string.tab_files, Icons.Outlined.Folder),
        Tab(R.string.tab_notes, Icons.Outlined.Description),
        Tab(R.string.tab_bookmarks, Icons.Outlined.Bookmarks),
        Tab(R.string.tab_todos, Icons.Outlined.CheckCircle),
    )
    var selected by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (selected) {
            0 -> FilesScreen(m)
            1 -> NotesScreen(m)
            2 -> BookmarksScreen(m)
            else -> TodosScreen(m)
        }
    }
}
