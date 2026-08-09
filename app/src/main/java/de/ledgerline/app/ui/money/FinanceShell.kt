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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.ArrowDropDown
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

/** A sub-screen pushed on top of the finance section (detail/edit/list flows). */
sealed interface MoneyRoute {
    data class InvoiceEdit(val id: Int?) : MoneyRoute
    data class TransactionEdit(val id: Int?) : MoneyRoute
    data object BulkImport : MoneyRoute
    data object Partners : MoneyRoute
    data object PaymentMethods : MoneyRoute
    data object Projects : MoneyRoute
    data object Company : MoneyRoute
    data object Insights : MoneyRoute
    data object Receipts : MoneyRoute
}

/**
 * The Finance module section: a top tab row (Dashboard / Invoices / Transactions / More) over the
 * shared [FinanceViewModel]. Detail/edit/list flows are pushed as [MoneyRoute] overlays via [onPush]
 * (hosted full-screen by [de.ledgerline.app.ui.shell.AppShell]). Embedded as the Finance tab.
 */
@Composable
fun FinanceSection(
    onPush: (MoneyRoute) -> Unit,
    modifier: Modifier = Modifier,
    vm: FinanceViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    Column(modifier.fillMaxSize()) {
        androidx.compose.material3.PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            Tab.entries.forEach { t ->
                androidx.compose.material3.Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(stringResource(t.labelRes)) },
                    icon = { Icon(t.icon, contentDescription = null) },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.DASHBOARD -> DashboardTab(vm, onOpenInvoices = { tab = Tab.INVOICES })
                Tab.INVOICES -> InvoicesTab(vm, onEdit = { onPush(MoneyRoute.InvoiceEdit(it)) })
                Tab.TRANSACTIONS -> TransactionsTab(vm, onEdit = { onPush(MoneyRoute.TransactionEdit(it)) }, onImport = { onPush(MoneyRoute.BulkImport) })
                Tab.MORE -> MoreTab(
                    onPartners = { onPush(MoneyRoute.Partners) },
                    onPaymentMethods = { onPush(MoneyRoute.PaymentMethods) },
                    onProjects = { onPush(MoneyRoute.Projects) },
                    onCompany = { onPush(MoneyRoute.Company) },
                    onInsights = { onPush(MoneyRoute.Insights) },
                    onReceipts = { onPush(MoneyRoute.Receipts) },
                )
            }
        }
    }
}

/** Hosts a pushed finance [MoneyRoute] full-screen (each screen owns its own back). */
@Composable
fun MoneyRouteHost(route: MoneyRoute, vm: FinanceViewModel, onBack: () -> Unit) {
    when (route) {
        is MoneyRoute.InvoiceEdit -> InvoiceEditScreen(vm, route.id, onBack)
        is MoneyRoute.TransactionEdit -> TransactionEditScreen(vm, route.id, onBack)
        MoneyRoute.BulkImport -> BulkImportScreen(vm, onBack)
        MoneyRoute.Partners -> PartnersScreen(vm, onBack)
        MoneyRoute.PaymentMethods -> PaymentMethodsScreen(vm, onBack)
        MoneyRoute.Projects -> ProjectsScreen(vm, onBack)
        MoneyRoute.Company -> CompanyScreen(vm, onBack)
        MoneyRoute.Insights -> InsightsScreen(vm, onBack)
        MoneyRoute.Receipts -> ReceiptsScreen(vm, onBack)
    }
}

// ---------------------------------------------------------------------------
//  Dashboard tab — server-computed KPIs + VAT for the year.
// ---------------------------------------------------------------------------
@Composable
private fun DashboardTab(vm: FinanceViewModel, onOpenInvoices: () -> Unit) {
    val reports by vm.reports.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val r = reports
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.dashboard_year_kpis), Modifier.weight(1f))
            YearPicker(current = r?.year ?: year, years = r?.years.orEmpty(), onPick = { vm.setYear(it) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KpiCard(stringResource(R.string.dashboard_revenue_net), FinanceViewModel.money(r?.kpis?.net ?: 0.0), Modifier.weight(1f))
            KpiCard(stringResource(R.string.dashboard_invoices), (r?.kpis?.count ?: 0).toString(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KpiCard(stringResource(R.string.dashboard_vat_payable), FinanceViewModel.money(r?.vat?.vat ?: 0.0), Modifier.weight(1f))
            KpiCard(stringResource(R.string.dashboard_customers), (r?.kpis?.customers ?: 0).toString(), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KpiCard(stringResource(R.string.dashboard_open_invoices), FinanceViewModel.money(r?.aging?.openGross ?: 0.0), Modifier.weight(1f))
            KpiCard(stringResource(R.string.dashboard_open_count), (r?.aging?.openCount ?: 0).toString(), Modifier.weight(1f))
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
private fun YearPicker(current: Int, years: List<Int>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = (years + current).distinct().sortedDescending()
    Box {
        androidx.compose.material3.TextButton(onClick = { open = true }) {
            Text(current.toString())
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { y ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(y.toString()) }, onClick = { open = false; onPick(y) })
            }
        }
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
    onInsights: () -> Unit,
    onReceipts: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MoreRow(stringResource(R.string.more_partners), Icons.Outlined.AccountBalance, Brand.tintBlue, onPartners)
        MoreRow(stringResource(R.string.more_payment_methods), Icons.Outlined.AccountBalance, Brand.tintGreen, onPaymentMethods)
        MoreRow(stringResource(R.string.more_projects), Icons.Outlined.Dashboard, Brand.tintOrange, onProjects)
        MoreRow(stringResource(R.string.more_receipts), Icons.AutoMirrored.Outlined.ReceiptLong, Brand.tintOrange, onReceipts)
        MoreRow(stringResource(R.string.more_insights), Icons.Outlined.Dashboard, Brand.tintViolet, onInsights)
        MoreRow(stringResource(R.string.more_company), Icons.Outlined.AccountBalance, Brand.tintTeal, onCompany)
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
