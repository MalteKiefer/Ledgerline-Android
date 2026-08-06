package de.ledgerline.app.ui.workspace

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
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
 * heavy surfaces (Files/Vault/Notes) live in a floating pill bar with a detached search key;
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

    // The account's module entitlements (rights model): hide nav for modules not allowed.
    val allowedModules by loader.allowedModules.collectAsStateWithLifecycle()
    val visible: (WorkspaceDest) -> Boolean = { d -> d.moduleKey == null || allowedModules?.contains(d.moduleKey) ?: true }
    // Server reachability (GET /up): false → offline mode (cache-only), retried every 60s.
    val serverOnline by loader.serverOnline.collectAsStateWithLifecycle()

    var dest by rememberSaveable { mutableStateOf(WorkspaceDest.Home) }
    var lastPrimary by rememberSaveable { mutableStateOf(WorkspaceDest.Home) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val fullscreen = remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val isPrimary = dest in WorkspaceDest.primary

    val navigate: (WorkspaceDest) -> Unit = { d ->
        // Never open a module the account isn't entitled to (Home tiles / rail / drawer all route here).
        if (visible(d)) {
            if (d in WorkspaceDest.primary) lastPrimary = d
            dest = d
        }
    }

    // If the server revokes access to the current screen, fall back to Home.
    LaunchedEffect(allowedModules) { if (!visible(dest)) { dest = WorkspaceDest.Home; lastPrimary = WorkspaceDest.Home } }

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
                visible = visible,
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
                    visible = visible,
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
                        WorkspaceDest.Calendar -> de.ledgerline.app.ui.calendar.CalendarScreen(onMenu = { scope.launch { drawerState.open() } })

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
          // Offline banner: the server (/up) is unreachable — cache-only, retrying every 60s.
          if (!serverOnline && !searchOpen) {
              Surface(
                  color = MaterialTheme.colorScheme.tertiaryContainer,
                  contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                  modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
              ) {
                  Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                      Icon(Icons.Outlined.CloudOff, null, Modifier.size(18.dp))
                      Spacer(Modifier.width(10.dp))
                      Text(stringResource(R.string.offline_server_unreachable), style = MaterialTheme.typography.bodyMedium)
                  }
              }
          }
        }
    }
}

/** Navigation rail for medium+ width: menu (drawer) + primary tabs + search. */
@Composable
private fun NavRail(
    current: WorkspaceDest,
    visible: (WorkspaceDest) -> Boolean,
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
        PRIMARY_TABS.filter { visible(it.dest) }.forEach { t ->
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
    PrimaryTab(WorkspaceDest.Vault, Icons.Outlined.Lock),
    PrimaryTab(WorkspaceDest.Notes, Icons.Outlined.Description),
)

@Composable
private fun DrawerSheet(current: WorkspaceDest, visible: (WorkspaceDest) -> Boolean, onSearch: () -> Unit, onSelect: (WorkspaceDest) -> Unit) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            // Brand hero: gradient tile with the shield logo, app name + version.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(13.dp))
                        .background(de.ledgerline.app.ui.theme.Brand.accentGradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_ledgerline_logo),
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.Unspecified,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    Text(
                        "v" + de.ledgerline.app.BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Prominent search pill.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(onClick = onSearch)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.search_everything), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Middle list scrolls if it can't fit; Settings stays pinned at the bottom.
            androidx.compose.foundation.layout.Column(
                Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 8.dp),
            ) {
                de.ledgerline.app.ui.common.SectionLabel(stringResource(R.string.drawer_group_cloud), Modifier.padding(start = 22.dp, top = 8.dp))
                listOf(
                    Triple(WorkspaceDest.Home, Icons.Outlined.Home, de.ledgerline.app.ui.theme.Brand.accent),
                    Triple(WorkspaceDest.Files, Icons.Outlined.Folder, de.ledgerline.app.ui.theme.Brand.tintBlue),
                    Triple(WorkspaceDest.Vault, Icons.Outlined.Lock, de.ledgerline.app.ui.theme.Brand.tintViolet),
                ).filter { visible(it.first) }.forEach { (d, ic, t) -> DrawerRow(d, ic, t, current, onSelect) }

                de.ledgerline.app.ui.common.SectionLabel(stringResource(R.string.drawer_group_workspace), Modifier.padding(start = 22.dp, top = 14.dp))
                listOf(
                    Triple(WorkspaceDest.Notes, Icons.Outlined.Description, de.ledgerline.app.ui.theme.Brand.tintViolet),
                    Triple(WorkspaceDest.Todos, Icons.Outlined.CheckCircle, de.ledgerline.app.ui.theme.Brand.tintGreen),
                    Triple(WorkspaceDest.Bookmarks, Icons.Outlined.Bookmarks, de.ledgerline.app.ui.theme.Brand.tintBlue),
                    Triple(WorkspaceDest.Contacts, Icons.Outlined.Contacts, de.ledgerline.app.ui.theme.Brand.tintTeal),
                    Triple(WorkspaceDest.Explore, Icons.Outlined.Map, de.ledgerline.app.ui.theme.Brand.tintTeal),
                    Triple(WorkspaceDest.Health, Icons.Outlined.MonitorHeart, de.ledgerline.app.ui.theme.Brand.tintGreen),
                    Triple(WorkspaceDest.Calendar, Icons.Outlined.CalendarMonth, de.ledgerline.app.ui.theme.Brand.tintOrange),
                    Triple(WorkspaceDest.Finance, Icons.Outlined.ReceiptLong, de.ledgerline.app.ui.theme.Brand.tintGreen),
                ).filter { visible(it.first) }.forEach { (d, ic, t) -> DrawerRow(d, ic, t, current, onSelect) }
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            DrawerRow(WorkspaceDest.Settings, Icons.Outlined.Settings, de.ledgerline.app.ui.theme.Brand.tintGray, current, onSelect)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DrawerRow(
    dest: WorkspaceDest,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    current: WorkspaceDest,
    onSelect: (WorkspaceDest) -> Unit,
) {
    val selected = dest == current
    NavigationDrawerItem(
        icon = { de.ledgerline.app.ui.theme.IconChip(icon, tint = tint, size = 30.dp) },
        label = { Text(stringResource(dest.labelRes)) },
        selected = selected,
        onClick = { onSelect(dest) },
        colors = androidx.compose.material3.NavigationDrawerItemDefaults.colors(
            selectedContainerColor = de.ledgerline.app.ui.theme.Brand.accent.copy(alpha = 0.14f),
            selectedTextColor = de.ledgerline.app.ui.theme.Brand.accent,
        ),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

