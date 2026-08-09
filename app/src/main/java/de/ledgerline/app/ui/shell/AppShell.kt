package de.ledgerline.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.R
import de.ledgerline.app.core.ModuleAccess
import de.ledgerline.app.data.AccountRepository
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.files.FilesSection
import de.ledgerline.app.ui.money.FinanceSection
import de.ledgerline.app.ui.money.FinanceViewModel
import de.ledgerline.app.ui.money.MoneyRoute
import de.ledgerline.app.ui.money.MoneyRouteHost
import de.ledgerline.app.ui.money.MoneySettingsScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Top-level module sections. [moduleKey] gates visibility against `/me.user.modules` (null = always). */
private enum class Section(val labelRes: Int, val icon: ImageVector, val moduleKey: String?) {
    FILES(R.string.tab_files, Icons.Outlined.Folder, "files"),
    FINANCE(R.string.tab_finance, Icons.AutoMirrored.Outlined.ReceiptLong, "finance"),
    ACCOUNT(R.string.tab_account, Icons.Outlined.AccountCircle, null),
}

@HiltViewModel
class ShellViewModel @Inject constructor(
    moduleAccess: ModuleAccess,
    private val accountRepository: AccountRepository,
) : ViewModel() {
    val allowed: StateFlow<Set<String>?> = moduleAccess.allowed
    init { viewModelScope.launch { accountRepository.me() } } // ensures modules (+ wipe flag) are loaded
}

/**
 * The signed-in app shell: a bottom nav across the enabled modules (Files / Finance) plus Account,
 * gated by [ModuleAccess]. Finance detail/edit flows are pushed full-screen over the shell. Replaces
 * the old finance-only FinanceShell now that the plaintext server exposes multiple modules.
 */
@Composable
fun AppShell(
    onDisconnected: () -> Unit,
    shellVm: ShellViewModel = hiltViewModel(),
    financeVm: FinanceViewModel = hiltViewModel(),
) {
    val allowed by shellVm.allowed.collectAsStateWithLifecycle()
    val visible = Section.entries.filter { it.moduleKey == null || allowed == null || it.moduleKey in allowed!! }

    var section by rememberSaveable { mutableStateOf(Section.FILES) }
    LaunchedEffect(visible) { if (section !in visible) section = visible.first() }

    var route by remember { mutableStateOf<MoneyRoute?>(null) }
    route?.let { r ->
        MoneyRouteHost(route = r, vm = financeVm, onBack = { route = null })
        return
    }

    AppScaffold(
        bottomBar = {
            NavigationBar {
                visible.forEach { s ->
                    NavigationBarItem(
                        selected = section == s,
                        onClick = { section = s },
                        icon = { Icon(s.icon, contentDescription = null) },
                        label = { Text(stringResource(s.labelRes)) },
                    )
                }
            }
        },
    ) { pad ->
        when (section) {
            Section.FILES -> FilesSection(contentPadding = pad)
            Section.FINANCE -> FinanceSection(onPush = { route = it }, modifier = Modifier.padding(pad), vm = financeVm)
            // The settings hub owns its own top bar; give it only bottom-nav clearance.
            Section.ACCOUNT -> Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
                MoneySettingsScreen(onBack = null, onLoggedOut = onDisconnected)
            }
        }
    }
}
