package de.ledgerline.app.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Work
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

/** Every finance area is its own icon tab in a scrollable tab row (no "More" bucket). */
private enum class Tab(val labelRes: Int, val icon: ImageVector) {
    DASHBOARD(R.string.tab_dashboard, Icons.Outlined.Dashboard),
    INVOICES(R.string.tab_invoices, Icons.AutoMirrored.Outlined.ReceiptLong),
    TRANSACTIONS(R.string.tab_transactions, Icons.Outlined.SwapHoriz),
    PARTNERS(R.string.more_partners, Icons.Outlined.Groups),
    PAYMENT_METHODS(R.string.more_payment_methods, Icons.Outlined.CreditCard),
    PROJECTS(R.string.more_projects, Icons.Outlined.Work),
    RECEIPTS(R.string.more_receipts, Icons.Outlined.Receipt),
    CATEGORIES(R.string.more_categories, Icons.Outlined.Sell),
    INSIGHTS(R.string.more_insights, Icons.Outlined.Insights),
    COMPANY(R.string.more_company, Icons.Outlined.Business),
    TRASH(R.string.more_trash, Icons.Outlined.Delete),
}

/**
 * A finance **editor** pushed full-screen over the section (each owns its own top bar + back).
 * Only create/edit forms push now — every list/read area renders inline as tab content.
 */
sealed interface MoneyRoute {
    data class InvoiceEdit(val id: Int?) : MoneyRoute
    data class TransactionEdit(val id: Int?) : MoneyRoute
    data object BulkImport : MoneyRoute
    data class PartnerEdit(val id: Int?) : MoneyRoute
    data class PaymentMethodEdit(val id: Int?) : MoneyRoute
    data class ProjectEdit(val id: Int?) : MoneyRoute
}

/**
 * The Finance module section: a **scrollable icon tab row** with one icon per area — no "More"
 * bucket. **Every** area renders inline as tab content; only create/edit forms (invoice, transaction,
 * partner, payment-method, project) and the CSV import push a full-screen [MoneyRoute] overlay.
 */
@Composable
fun FinanceSection(
    onPush: (MoneyRoute) -> Unit,
    modifier: Modifier = Modifier,
    vm: FinanceViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    Column(modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.tab_finance))
        androidx.compose.material3.PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
            Tab.entries.forEach { t ->
                androidx.compose.material3.Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = { Icon(t.icon, contentDescription = stringResource(t.labelRes)) },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.DASHBOARD -> DashboardTab(vm, onOpenInvoices = { tab = Tab.INVOICES })
                Tab.INVOICES -> InvoicesTab(vm, onEdit = { onPush(MoneyRoute.InvoiceEdit(it)) })
                Tab.TRANSACTIONS -> TransactionsTab(vm, onEdit = { onPush(MoneyRoute.TransactionEdit(it)) }, onImport = { onPush(MoneyRoute.BulkImport) })
                Tab.PARTNERS -> PartnersTab(vm, onEdit = { onPush(MoneyRoute.PartnerEdit(it)) })
                Tab.PAYMENT_METHODS -> PaymentMethodsTab(vm, onEdit = { onPush(MoneyRoute.PaymentMethodEdit(it)) })
                Tab.PROJECTS -> ProjectsTab(vm, onEdit = { onPush(MoneyRoute.ProjectEdit(it)) })
                Tab.RECEIPTS -> ReceiptsTab(vm)
                Tab.CATEGORIES -> CategoriesTab(vm)
                Tab.INSIGHTS -> InsightsTab(vm)
                Tab.COMPANY -> CompanyTab(vm)
                Tab.TRASH -> TrashTab(vm)
            }
        }
    }
}

/** Hosts a pushed finance editor [MoneyRoute] overlay full-screen (each screen owns its own back). */
@Composable
fun MoneyRouteHost(route: MoneyRoute, vm: FinanceViewModel, onBack: () -> Unit) {
    when (route) {
        is MoneyRoute.InvoiceEdit -> InvoiceEditScreen(vm, route.id, onBack)
        is MoneyRoute.TransactionEdit -> TransactionEditScreen(vm, route.id, onBack)
        MoneyRoute.BulkImport -> BulkImportScreen(vm, onBack)
        is MoneyRoute.PartnerEdit -> PartnerEditScreen(vm, route.id, onBack)
        is MoneyRoute.PaymentMethodEdit -> PaymentMethodEditScreen(vm, route.id, onBack)
        is MoneyRoute.ProjectEdit -> ProjectEditScreen(vm, route.id, onBack)
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
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    de.ledgerline.app.ui.common.RefreshBox(refreshing = refreshing, onRefresh = { vm.pullRefresh() }) {
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
        r?.kpis?.growthPct?.let { g ->
            KpiCard(stringResource(R.string.dashboard_growth), (if (g >= 0) "+" else "") + String.format(java.util.Locale.US, "%.1f%%", g), Modifier.fillMaxWidth())
        }

        // Aging breakdown of open receivables.
        r?.aging?.buckets?.let { b ->
            if (b.current.count + b.d1_30.count + b.d31_60.count + b.d60_plus.count > 0) {
                SectionLabel(stringResource(R.string.dashboard_aging))
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgingRow(stringResource(R.string.aging_current), b.current)
                    AgingRow(stringResource(R.string.aging_1_30), b.d1_30)
                    AgingRow(stringResource(R.string.aging_31_60), b.d31_60)
                    AgingRow(stringResource(R.string.aging_60_plus), b.d60_plus)
                }
            }
        }

        // Monthly revenue chart.
        r?.months?.takeIf { it.any { m -> m.net > 0 } }?.let { months ->
            SectionLabel(stringResource(R.string.dashboard_monthly))
            MonthlyChart(months, Modifier.fillMaxWidth().cardSurface())
        }

        SectionLabel(stringResource(R.string.dashboard_top_customers))
        Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val customers = r?.customers.orEmpty()
            if (customers.isEmpty()) {
                Text(stringResource(R.string.dashboard_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else customers.forEach { c ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(FinanceViewModel.money(c.net), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
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

/** Simple 12-month revenue bar chart (net per month), scaled to the max month. */
@Composable
private fun MonthlyChart(months: List<de.ledgerline.app.domain.model.finance.MonthRevenue>, modifier: Modifier = Modifier) {
    val max = (months.maxOfOrNull { it.net } ?: 0.0).coerceAtLeast(1.0)
    val accent = Brand.accent
    Row(
        modifier.height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Bottom,
    ) {
        months.sortedBy { it.month }.forEach { m ->
            val frac = (m.net / max).coerceIn(0.0, 1.0).toFloat()
            Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(
                    Modifier.fillMaxWidth(0.7f).fillMaxHeight(frac.coerceAtLeast(0.02f))
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(accent),
                )
                Text(m.month.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AgingRow(label: String, bucket: de.ledgerline.app.domain.model.finance.AgingBucket) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$label (${bucket.count})", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(FinanceViewModel.money(bucket.gross), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun KpiCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.cardSurface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}
