package de.ledgerline.app.ui.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.core.finance.FinanceStats
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.cardSurface

/**
 * Finance statistics for the selected [year]: headline KPIs (with year-over-year growth), the VAT
 * advance return (net/VAT/gross, by rate + by quarter), monthly net-revenue bars, and revenue by
 * customer. All figures come from [FinanceStats] over the realized (sent/paid) invoices.
 */
@Composable
fun FinanceStatsScreen(vm: FinanceViewModel, year: Int, onBack: () -> Unit) {
    val kpis = vm.statsKpis(year)
    val vat = vm.vatReturn(year)
    val accountVat = vm.accountVat(year)
    val monthly = vm.monthlyRevenue(year)
    val customers = vm.revenueByCustomer(year)

    AppScaffold(
        topBar = { AppTopBar(title = stringResource(R.string.finance_stats_title, year), onBack = onBack) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.size(2.dp))

            // Headline KPIs
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(vm.money(kpis.net, null), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Brand.accent)
                Text(stringResource(R.string.finance_stats_net_revenue), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                kpis.growthPct?.let { g ->
                    val up = g >= 0
                    Text(
                        stringResource(if (up) R.string.finance_stats_growth_up else R.string.finance_stats_growth_down, kotlin.math.abs(g)),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (up) Color(0xFF59AD6B) else Color(0xFFE2915A),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MiniStat(kpis.count.toString(), stringResource(R.string.finance_stats_invoices))
                    MiniStat(vm.money(kpis.avg, null), stringResource(R.string.finance_stats_avg))
                    MiniStat(kpis.customers.toString(), stringResource(R.string.finance_stats_customers))
                }
            }

            // VAT advance return
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.finance_stats_vat_title), style = MaterialTheme.typography.labelMedium, color = Brand.accent)
                TotalRow(stringResource(R.string.finance_stats_net), vm.money(vat.net, null))
                TotalRow(stringResource(R.string.finance_stats_vat), vm.money(vat.vat, null))
                TotalRow(stringResource(R.string.finance_stats_gross), vm.money(vat.gross, null), bold = true)
                if (vat.byRate.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    vat.byRate.forEach { r ->
                        TotalRow(
                            stringResource(R.string.finance_stats_rate, trimRate(r.rate)),
                            vm.money(r.vat, null) + "  ·  " + vm.money(r.net, null),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    vat.quarters.forEach { q ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Q${q.q}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(vm.money(q.net, null), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            }

            // VAT payable from bookings (Umsatzsteuer-Zahllast), if any categorised bookings exist.
            if (accountVat.outputVat != 0.0 || accountVat.inputVat != 0.0 || accountVat.undecided > 0) {
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.finance_stats_vat_payable_title), style = MaterialTheme.typography.labelMedium, color = Brand.accent)
                    TotalRow(stringResource(R.string.finance_stats_output_vat), vm.money(accountVat.outputVat, null))
                    TotalRow(stringResource(R.string.finance_stats_input_vat), vm.money(accountVat.inputVat, null))
                    TotalRow(stringResource(R.string.finance_stats_vat_payable), vm.money(accountVat.payable, null), bold = true)
                    if (accountVat.undecided > 0) {
                        Text(
                            stringResource(R.string.finance_stats_vat_undecided, accountVat.undecided),
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2915A),
                        )
                    }
                }
            }

            // Monthly revenue bars
            if (monthly.any { it > 0.0 }) {
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.finance_stats_monthly), style = MaterialTheme.typography.labelMedium, color = Brand.accent)
                    MonthlyBars(monthly)
                }
            }

            // Revenue by customer
            if (customers.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
                    Text(
                        stringResource(R.string.finance_stats_by_customer),
                        style = MaterialTheme.typography.labelMedium, color = Brand.accent,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                    )
                    customers.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(
                                    stringResource(R.string.finance_stats_customer_count, c.count),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(vm.money(c.net, null), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TotalRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun MonthlyBars(monthly: List<Double>) {
    val max = (monthly.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val labels = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        monthly.forEachIndexed { i, v ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                val frac = (v / max).toFloat().coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth().height((4 + frac * 92).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (v > 0) Brand.accentGradient else Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant))),
                )
                Spacer(Modifier.size(4.dp))
                Text(labels[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** `19` for whole rates, `7.5` otherwise. */
private fun trimRate(r: Double): String = if (r == kotlin.math.floor(r)) r.toLong().toString() else r.toString()
