package de.ledgerline.app.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.cardSurface

/** Read-only invoice detail: recipient, line items, and computed totals in grouped cards. */
@Composable
fun InvoiceDetailScreen(inv: Invoice, vm: FinanceViewModel, onBack: () -> Unit) {
    val totals = vm.totals(inv)
    AppScaffold(
        topBar = { AppTopBar(title = inv.number ?: stringResource(R.string.finance_no_number), onBack = onBack) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Status + dates
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LabeledLine(stringResource(R.string.finance_status), statusLabelText(inv))
                if (inv.issueDate.isNotBlank()) LabeledLine(stringResource(R.string.finance_issue_date), inv.issueDate)
                if (inv.dueDate.isNotBlank()) LabeledLine(stringResource(R.string.finance_due_date), inv.dueDate)
            }

            // Recipient
            if (inv.customer.name.isNotBlank() || inv.customer.address.isNotBlank()) {
                Column(Modifier.fillMaxWidth().cardSurface()) {
                    Text(stringResource(R.string.finance_recipient), style = MaterialTheme.typography.labelMedium, color = Brand.accent)
                    Spacer(Modifier.width(4.dp))
                    if (inv.customer.name.isNotBlank()) Text(inv.customer.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    if (inv.customer.attn.isNotBlank()) Text(inv.customer.attn, style = MaterialTheme.typography.bodyMedium)
                    if (inv.customer.address.isNotBlank()) Text(inv.customer.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (inv.customer.email.isNotBlank()) Text(inv.customer.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (inv.customer.vatId.isNotBlank()) Text("USt-IdNr: ${inv.customer.vatId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Line items
            Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
                Text(stringResource(R.string.finance_items), style = MaterialTheme.typography.labelMedium, color = Brand.accent, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
                inv.lines.forEachIndexed { i, l ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(l.desc.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${trimNum(l.qty)}${if (l.unit.isNotBlank()) " " + l.unit else ""} × ${vm.money(l.unitPrice, inv.currency)}  ·  ${trimNum(l.vatRate)}%",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(vm.money(l.qty * l.unitPrice, inv.currency), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.width(6.dp))
            }

            // Totals
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TotalLine(stringResource(R.string.finance_net), vm.money(totals.net, inv.currency), bold = false)
                totals.vatByRate.toSortedMap().forEach { (rate, v) ->
                    TotalLine("${stringResource(R.string.finance_vat)} ${trimNum(rate)}%", vm.money(v, inv.currency), bold = false)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                TotalLine(stringResource(R.string.finance_gross), vm.money(totals.gross, inv.currency), bold = true)
            }

            if (inv.note.isNotBlank()) {
                Column(Modifier.fillMaxWidth().cardSurface()) {
                    Text(stringResource(R.string.finance_note), style = MaterialTheme.typography.labelMedium, color = Brand.accent)
                    Text(inv.note, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun LabeledLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TotalLine(label: String, value: String, bold: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(value, style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun statusLabelText(inv: Invoice): String = statusLabel(inv.status)

/** Format a number without a trailing `.0` (e.g. 2.0 → "2", 19.0 → "19", 1.5 → "1.5"). */
private fun trimNum(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
