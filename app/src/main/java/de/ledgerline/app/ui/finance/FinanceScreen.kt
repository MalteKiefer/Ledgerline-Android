package de.ledgerline.app.ui.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.InvoiceStatus
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.cardSurface

private fun statusColor(s: InvoiceStatus): Color = when (s) {
    InvoiceStatus.PAID -> Color(0xFF59AD6B)
    InvoiceStatus.SENT -> Color(0xFFE2915A)
    else -> Color(0xFF6B7280)
}

@Composable
internal fun statusLabel(s: InvoiceStatus): String = stringResource(
    when (s) {
        InvoiceStatus.PAID -> R.string.finance_status_paid
        InvoiceStatus.SENT -> R.string.finance_status_sent
        else -> R.string.finance_status_draft
    },
)

@Composable
fun FinanceScreen(modifier: Modifier = Modifier, onMenu: (() -> Unit)? = null, vm: FinanceViewModel = hiltViewModel()) {
    var openId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Invoice?>(null) }
    var editCompany by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showPayments by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var showPartners by remember { mutableStateOf(false) }
    val editingInv = editing
    val current = openId?.let { vm.invoiceById(it) }
    val year by vm.year.collectAsStateWithLifecycle()
    when {
        editCompany -> CompanyEditScreen(vm, onBack = { editCompany = false })
        showStats -> FinanceStatsScreen(vm, year, onBack = { showStats = false })
        showPayments -> PaymentMethodsScreen(vm, onBack = { showPayments = false })
        showProjects -> ProjectsScreen(vm, onBack = { showProjects = false })
        showPartners -> PartnersScreen(vm, onBack = { showPartners = false })
        editingInv != null -> InvoiceEditScreen(editingInv, vm, onCancel = { editing = null }, onSaved = { editing = null; openId = null })
        current != null -> InvoiceDetailScreen(current, vm, onBack = { openId = null }, onEdit = { editing = current })
        else -> FinanceList(vm, modifier, onMenu = onMenu, onOpen = { openId = it }, onNew = { editing = vm.newDraft() }, onCompany = { editCompany = true }, onStats = { showStats = true }, onPayments = { showPayments = true }, onProjects = { showProjects = true }, onPartners = { showPartners = true }, onImported = { editing = it })
    }
}

@Composable
private fun FinanceList(vm: FinanceViewModel, modifier: Modifier, onMenu: (() -> Unit)?, onOpen: (String) -> Unit, onNew: () -> Unit, onCompany: () -> Unit, onStats: () -> Unit, onPayments: () -> Unit, onProjects: () -> Unit, onPartners: () -> Unit, onImported: (Invoice) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "invoice"
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        if (bytes == null) { android.widget.Toast.makeText(ctx, ctx.getString(R.string.finance_import_xml_bad), android.widget.Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
        vm.importInvoiceDocument(bytes, name, mime) { inv ->
            if (inv != null) onImported(inv)
            else android.widget.Toast.makeText(ctx, ctx.getString(R.string.finance_import_xml_bad), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val invoices by vm.invoices.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    val scope by vm.financeScope.collectAsStateWithLifecycle()
    val years = remember(invoices) { (vm.years() + year).distinct().sortedDescending() }
    val list = remember(invoices, year, scope) { vm.invoicesOf(year) }
    val kpis = remember(invoices, year) { vm.kpis(year) }

    AppScaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.dest_finance),
                onMenu = onMenu,
                actions = {
                    androidx.compose.material3.IconButton(onClick = { vm.load() }) {
                        Icon(androidx.compose.material.icons.Icons.Outlined.Refresh, stringResource(R.string.action_refresh))
                    }
                    androidx.compose.material3.IconButton(onClick = onStats) {
                        Icon(Icons.Outlined.QueryStats, stringResource(R.string.finance_stats_action))
                    }
                    androidx.compose.material3.IconButton(onClick = onPayments) {
                        Icon(Icons.Outlined.AccountBalanceWallet, stringResource(R.string.finance_pm_title))
                    }
                    var menu by remember { mutableStateOf(false) }
                    androidx.compose.material3.IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more))
                    }
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.finance_projects)) }, leadingIcon = { Icon(Icons.Outlined.AccountTree, null) }, onClick = { menu = false; onProjects() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.finance_partners)) }, leadingIcon = { Icon(Icons.Outlined.Groups, null) }, onClick = { menu = false; onPartners() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.finance_import_xml)) }, leadingIcon = { Icon(Icons.Outlined.FileOpen, null) }, onClick = { menu = false; importLauncher.launch(arrayOf("text/xml", "application/xml", "*/*")) })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.finance_company)) }, leadingIcon = { Icon(Icons.Outlined.Business, null) }, onClick = { menu = false; onCompany() })
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
        de.ledgerline.app.ui.common.PullRefresh(onRefresh = { vm.load() }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.size(4.dp))
            // Global business/private scope — filters invoices, payment methods, projects, stats.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to R.string.finance_scope_all, "business" to R.string.finance_scope_business, "private" to R.string.finance_scope_private).forEach { (k, res) ->
                    FilterChip(selected = scope == k, onClick = { vm.setFinanceScope(k) }, label = { Text(stringResource(res)) })
                }
            }
            // Year selector
            if (years.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    years.forEach { y -> FilterChip(selected = y == year, onClick = { vm.setYear(y) }, label = { Text(y.toString()) }) }
                }
            }
            // KPI card
            Row(Modifier.fillMaxWidth().cardSurface(), horizontalArrangement = Arrangement.SpaceBetween) {
                Kpi(stringResource(R.string.finance_kpi_paid), vm.money(kpis.paidYear, null), Color(0xFF59AD6B))
                Kpi(stringResource(R.string.finance_kpi_outstanding), vm.money(kpis.outstandingYear, null), Color(0xFFE2915A))
                Kpi(stringResource(R.string.finance_kpi_count), kpis.countYear.toString(), Brand.accent)
            }

            if (list.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.finance_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
                    list.forEachIndexed { i, inv ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        InvoiceRow(inv, vm, onClick = { onOpen(inv.id) })
                    }
                }
            }
        }
        }
        FloatingActionButton(
            onClick = onNew,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Outlined.Add, stringResource(R.string.finance_new)) }
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InvoiceRow(inv: Invoice, vm: FinanceViewModel, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        de.ledgerline.app.ui.theme.IconChip(Icons.Outlined.ReceiptLong, tint = statusColor(inv.status), size = 34.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(inv.number ?: stringResource(R.string.finance_no_number), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                (inv.customer.name.ifBlank { stringResource(R.string.finance_no_customer) }) + " · " + inv.issueDate,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(vm.money(vm.totals(inv).gross, inv.currency), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(statusLabel(inv.status), style = MaterialTheme.typography.labelSmall, color = statusColor(inv.status))
        }
    }
}
