package de.ledgerline.app.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import de.ledgerline.app.R
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.gallery.GalleryScreen
import de.ledgerline.app.ui.home.HomeScreen
import de.ledgerline.app.ui.search.SearchScreen
import de.ledgerline.app.ui.settings.SettingsContent
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.workspace.bookmarks.BookmarksScreen
import de.ledgerline.app.ui.workspace.contacts.ContactsScreen
import de.ledgerline.app.ui.workspace.files.FilesScreen
import de.ledgerline.app.ui.workspace.notes.NotesScreen
import de.ledgerline.app.ui.workspace.todos.TodosScreen
import kotlinx.coroutines.launch

/**
 * The redesigned shell (Material 3 Expressive, hub-and-spoke). A [HomeScreen] hub plus three
 * heavy surfaces (Files/Photos/Vault) live in a floating pill bar with a detached search key;
 * the long tail (Notes/Todos/Bookmarks/Contacts/Settings) opens from the navigation drawer and
 * the Home tiles. A nested full-screen view collapses the pill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScaffold(
    onLockNow: () -> Unit = {},
    onDisconnected: () -> Unit = {},
) {
    val loader: WorkspaceViewModel = hiltViewModel()
    LaunchedEffect(Unit) { loader.ensureLoaded() }

    var dest by rememberSaveable { mutableStateOf(WorkspaceDest.Home) }
    var lastPrimary by rememberSaveable { mutableStateOf(WorkspaceDest.Home) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val fullscreen = remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val isPrimary = dest in WorkspaceDest.primary

    val navigate: (WorkspaceDest) -> Unit = { d ->
        if (d in WorkspaceDest.primary) lastPrimary = d
        dest = d
    }

    // Back: search first, then non-home → home/lastPrimary.
    BackHandler(enabled = searchOpen) { searchOpen = false }
    BackHandler(enabled = !searchOpen && dest != WorkspaceDest.Home) {
        dest = if (isPrimary) WorkspaceDest.Home else lastPrimary
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !fullscreen.value && !searchOpen,
        drawerContent = {
            DrawerSheet(
                current = dest,
                onSearch = { scope.launch { drawerState.close() }; searchOpen = true },
                onSelect = { d -> scope.launch { drawerState.close() }; navigate(d) },
            )
        },
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
          // Adaptive: a navigation rail replaces the pill on medium+ width (tablets/foldables).
          val wide = maxWidth >= 600.dp
          Row(Modifier.fillMaxSize()) {
            if (wide && !fullscreen.value) {
                NavRail(
                    current = dest,
                    onSelect = navigate,
                    onMenu = { scope.launch { drawerState.open() } },
                    onSearch = { searchOpen = true },
                )
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
            // Drawer is the primary navigation now (no bottom pill). Module screens get a single
            // clean bar: drawer (menu) + title. Each screen owns its own in-context search/actions
            // (no duplicated top-bar magnifier); global cross-module search lives on Home + the
            // drawer's search row. An optional [actions] slot lets a screen fold its contextual
            // controls into this one bar instead of adding a second toolbar underneath.
            val primaryBar: @Composable (Int, @Composable RowScope.() -> Unit) -> Unit = { titleRes, actions ->
                if (!fullscreen.value) {
                    AppTopBar(
                        title = stringResource(titleRes),
                        onMenu = { scope.launch { drawerState.open() } },
                        actions = actions,
                    )
                }
            }
            CompositionLocalProvider(LocalFullscreen provides fullscreen) {
                AnimatedContent(
                    targetState = dest,
                    transitionSpec = { fadeIn(tween(200, delayMillis = 80)) togetherWith fadeOut(tween(90)) },
                    label = "dest",
                ) { d ->
                    when (d) {
                        WorkspaceDest.Home -> AppScaffold(
                            topBar = {
                                AppTopBar(
                                    title = stringResource(R.string.app_name),
                                    onMenu = { scope.launch { drawerState.open() } },
                                    actions = {
                                        androidx.compose.material3.IconButton(onClick = { searchOpen = true }) {
                                            Icon(Icons.Outlined.Search, stringResource(R.string.search_everything))
                                        }
                                    },
                                )
                            },
                        ) { p -> HomeScreen(Modifier.padding(p), onOpen = navigate) }

                        WorkspaceDest.Files -> FilesScreen(onMenu = { scope.launch { drawerState.open() } })
                        WorkspaceDest.Photos -> GalleryScreen(onMenu = { scope.launch { drawerState.open() } })
                        WorkspaceDest.Vault -> de.ledgerline.app.ui.passwords.PasswordsScreen(onMenu = { scope.launch { drawerState.open() } })

                        WorkspaceDest.Notes -> AppScaffold(
                            immersive = fullscreen.value,
                            topBar = { primaryBar(R.string.tab_notes) {} },
                        ) { p -> NotesScreen(Modifier.padding(p)) }

                        WorkspaceDest.Todos -> AppScaffold(
                            topBar = { primaryBar(R.string.tab_todos) {} },
                        ) { p -> TodosScreen(Modifier.padding(p)) }

                        WorkspaceDest.Bookmarks -> AppScaffold(
                            immersive = fullscreen.value,
                            topBar = { primaryBar(R.string.menu_bookmarks) {} },
                        ) { p -> BookmarksScreen(Modifier.padding(p)) }

                        WorkspaceDest.Contacts -> ContactsScreen(onMenu = { scope.launch { drawerState.open() } })

                        WorkspaceDest.Explore -> de.ledgerline.app.ui.explore.ExploreScreen(onMenu = { scope.launch { drawerState.open() } })

                        WorkspaceDest.Health -> de.ledgerline.app.ui.health.HealthScreen(onMenu = { scope.launch { drawerState.open() } })

                        WorkspaceDest.Finance -> de.ledgerline.app.ui.finance.FinanceScreen(onMenu = { scope.launch { drawerState.open() } })

                        WorkspaceDest.Settings -> SettingsContent(
                            onLockNow = onLockNow,
                            onDisconnected = onDisconnected,
                            onBack = { dest = lastPrimary },
                        )
                    }
                }
            }

            } // content Box
          } // Row
          // Global search overlay covers everything (incl. the rail).
          if (searchOpen) {
              SearchScreen(
                  onOpen = { d -> searchOpen = false; navigate(d) },
                  onBack = { searchOpen = false },
                  modifier = Modifier.fillMaxSize(),
              )
          }
        }
    }
}

/** Navigation rail for medium+ width: menu (drawer) + primary tabs + search. */
@Composable
private fun NavRail(
    current: WorkspaceDest,
    onSelect: (WorkspaceDest) -> Unit,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
) {
    androidx.compose.material3.NavigationRail(
        header = {
            androidx.compose.material3.IconButton(onClick = onMenu) {
                Icon(Icons.Outlined.Menu, stringResource(R.string.menu_more))
            }
            androidx.compose.material3.FloatingActionButton(
                onClick = onSearch,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Brand.accent,
                elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(2.dp),
            ) { Icon(Icons.Outlined.Search, stringResource(R.string.search_everything)) }
        },
    ) {
        PRIMARY_TABS.forEach { t ->
            androidx.compose.material3.NavigationRailItem(
                selected = current == t.dest,
                onClick = { onSelect(t.dest) },
                icon = { Icon(t.icon, null) },
                label = { Text(stringResource(t.dest.labelRes)) },
            )
        }
    }
}

private data class PrimaryTab(val dest: WorkspaceDest, val icon: ImageVector)

private val PRIMARY_TABS = listOf(
    PrimaryTab(WorkspaceDest.Home, Icons.Outlined.Home),
    PrimaryTab(WorkspaceDest.Files, Icons.Outlined.Folder),
    PrimaryTab(WorkspaceDest.Photos, Icons.Outlined.PhotoLibrary),
    PrimaryTab(WorkspaceDest.Vault, Icons.Outlined.Lock),
)

@Composable
private fun DrawerSheet(current: WorkspaceDest, onSearch: () -> Unit, onSelect: (WorkspaceDest) -> Unit) {
    ModalDrawerSheet {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            // Brand header: app name + version.
            Row(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Text(
                    "v" + de.ledgerline.app.BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Search, null) },
                label = { Text(stringResource(R.string.search_everything)) },
                selected = false,
                onClick = onSearch,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            // Middle list scrolls if it can't fit; Settings stays pinned at the bottom.
            androidx.compose.foundation.layout.Column(
                Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                androidx.compose.material3.HorizontalDivider(Modifier.padding(16.dp))
                listOf(
                    WorkspaceDest.Home to Icons.Outlined.Home,
                    WorkspaceDest.Files to Icons.Outlined.Folder,
                    WorkspaceDest.Photos to Icons.Outlined.PhotoLibrary,
                    WorkspaceDest.Vault to Icons.Outlined.Lock,
                ).forEach { (d, ic) -> DrawerRow(d, ic, current, onSelect) }

                androidx.compose.material3.HorizontalDivider(Modifier.padding(16.dp))
                listOf(
                    WorkspaceDest.Notes to Icons.Outlined.Description,
                    WorkspaceDest.Todos to Icons.Outlined.CheckCircle,
                    WorkspaceDest.Bookmarks to Icons.Outlined.Bookmarks,
                    WorkspaceDest.Contacts to Icons.Outlined.Contacts,
                    WorkspaceDest.Explore to Icons.Outlined.Map,
                    WorkspaceDest.Health to Icons.Outlined.MonitorHeart,
                    WorkspaceDest.Finance to Icons.Outlined.ReceiptLong,
                ).forEach { (d, ic) -> DrawerRow(d, ic, current, onSelect) }
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(16.dp))
            DrawerRow(WorkspaceDest.Settings, Icons.Outlined.Settings, current, onSelect)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DrawerRow(dest: WorkspaceDest, icon: ImageVector, current: WorkspaceDest, onSelect: (WorkspaceDest) -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, null) },
        label = { Text(stringResource(dest.labelRes)) },
        selected = dest == current,
        onClick = { onSelect(dest) },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

