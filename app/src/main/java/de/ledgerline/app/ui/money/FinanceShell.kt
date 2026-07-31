package de.ledgerline.app.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.IconChip
import de.ledgerline.app.ui.theme.cardSurface

private enum class Tab(val labelRes: Int, val icon: ImageVector) {
    DASHBOARD(R.string.tab_dashboard, Icons.Outlined.Dashboard),
    INVOICES(R.string.tab_invoices, Icons.AutoMirrored.Outlined.ReceiptLong),
    TRANSACTIONS(R.string.tab_transactions, Icons.Outlined.SwapHoriz),
    MORE(R.string.tab_more, Icons.Outlined.MoreHoriz),
}

/** A sub-screen pushed on top of the tabbed shell (detail/edit/list flows). */
sealed interface MoneyRoute {
    data class InvoiceEdit(val id: Int?) : MoneyRoute
    data class TransactionEdit(val id: Int?) : MoneyRoute
    data object Partners : MoneyRoute
    data object PaymentMethods : MoneyRoute
    data object Projects : MoneyRoute
    data object Company : MoneyRoute
    data object Settings : MoneyRoute
}

/**
 * Finance-only app shell (server pivot). A 4-tab bottom bar (Dashboard / Invoices / Transactions /
 * More) over the shared [FinanceViewModel], with detail/edit/list flows pushed as [MoneyRoute]
 * overlays. Replaces the old zero-knowledge WorkspaceScaffold.
 */
@Composable
fun FinanceShell(
    onLockNow: () -> Unit,
    onDisconnected: () -> Unit,
    vm: FinanceViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    var route by remember { mutableStateOf<MoneyRoute?>(null) }

    // A pushed route owns the whole screen.
    route?.let { r ->
        MoneyRouteHost(route = r, vm = vm, onBack = { route = null }, onLockNow = onLockNow, onDisconnected = onDisconnected)
        return
    }

    AppScaffold(
        topBar = { AppTopBar(title = stringResource(tab.labelRes)) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.labelRes)) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                Tab.DASHBOARD -> DashboardTab(vm, onOpenInvoices = { tab = Tab.INVOICES })
                Tab.INVOICES -> InvoicesTab(vm, onEdit = { route = MoneyRoute.InvoiceEdit(it) })
                Tab.TRANSACTIONS -> TransactionsTab(vm, onEdit = { route = MoneyRoute.TransactionEdit(it) })
                Tab.MORE -> MoreTab(
                    onPartners = { route = MoneyRoute.Partners },
                    onPaymentMethods = { route = MoneyRoute.PaymentMethods },
                    onProjects = { route = MoneyRoute.Projects },
                    onCompany = { route = MoneyRoute.Company },
                    onSettings = { route = MoneyRoute.Settings },
                )
            }
        }
    }
}

@Composable
private fun MoneyRouteHost(
    route: MoneyRoute,
    vm: FinanceViewModel,
    onBack: () -> Unit,
    onLockNow: () -> Unit,
    onDisconnected: () -> Unit,
) {
    when (route) {
        is MoneyRoute.InvoiceEdit -> InvoiceEditScreen(vm, route.id, onBack)
        is MoneyRoute.TransactionEdit -> TransactionEditScreen(vm, route.id, onBack)
        MoneyRoute.Partners -> PartnersScreen(vm, onBack)
        MoneyRoute.PaymentMethods -> PaymentMethodsScreen(vm, onBack)
        MoneyRoute.Projects -> ProjectsScreen(vm, onBack)
        MoneyRoute.Company -> CompanyScreen(vm, onBack)
        MoneyRoute.Settings -> MoneySettingsScreen(onBack = onBack, onLoggedOut = onDisconnected)
    }
}

// ---------------------------------------------------------------------------
//  Dashboard tab — server-computed KPIs + VAT for the year.
// ---------------------------------------------------------------------------
@Composable
private fun DashboardTab(vm: FinanceViewModel, onOpenInvoices: () -> Unit) {
    val reports by vm.reports.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val r = reports
        SectionLabel(stringResource(R.string.dashboard_year_kpis) + " " + (r?.year ?: vm.year.value).toString())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KpiCard(stringResource(R.string.dashboard_revenue_net), FinanceViewModel.money(r?.kpis?.net ?: 0.0), Modifier.weight(1f))
            KpiCard(stringResource(R.string.dashboard_invoices), (r?.kpis?.count ?: 0).toString(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KpiCard(stringResource(R.string.dashboard_vat_payable), FinanceViewModel.money(r?.vat?.vat ?: 0.0), Modifier.weight(1f))
            KpiCard(stringResource(R.string.dashboard_customers), (r?.kpis?.customers ?: 0).toString(), Modifier.weight(1f))
        }

        SectionLabel(stringResource(R.string.dashboard_top_customers))
        Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val customers = r?.customers.orEmpty().take(5)
            if (customers.isEmpty()) {
                Text(stringResource(R.string.dashboard_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else customers.forEach { c ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium)
                    Text(FinanceViewModel.money(c.net), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun KpiCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.cardSurface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

// ---------------------------------------------------------------------------
//  More tab — entries into the sub-screens.
// ---------------------------------------------------------------------------
@Composable
private fun MoreTab(
    onPartners: () -> Unit,
    onPaymentMethods: () -> Unit,
    onProjects: () -> Unit,
    onCompany: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MoreRow(stringResource(R.string.more_partners), Icons.Outlined.AccountBalance, Brand.tintBlue, onPartners)
        MoreRow(stringResource(R.string.more_payment_methods), Icons.Outlined.AccountBalance, Brand.tintGreen, onPaymentMethods)
        MoreRow(stringResource(R.string.more_projects), Icons.Outlined.Dashboard, Brand.tintOrange, onProjects)
        MoreRow(stringResource(R.string.more_company), Icons.Outlined.AccountBalance, Brand.tintTeal, onCompany)
        MoreRow(stringResource(R.string.more_settings), Icons.Outlined.MoreHoriz, Brand.tintGray, onSettings)
    }
}

@Composable
private fun MoreRow(label: String, icon: ImageVector, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(Brand.cardRadius))
            .clickable(onClick = onClick)
            .cardSurface(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconChip(icon = icon, tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
