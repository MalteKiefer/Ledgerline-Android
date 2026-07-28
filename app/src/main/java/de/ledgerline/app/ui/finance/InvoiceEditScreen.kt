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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.Invoice
import de.ledgerline.app.domain.model.InvoiceLine
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.PrimaryGradientButton
import de.ledgerline.app.ui.theme.cardSurface

/** Editable line as strings (numeric fields parsed on save). */
private class LineDraft(desc: String, qty: String, unit: String, unitPrice: String, vatRate: String) {
    var desc by mutableStateOf(desc)
    var qty by mutableStateOf(qty)
    var unit by mutableStateOf(unit)
    var unitPrice by mutableStateOf(unitPrice)
    var vatRate by mutableStateOf(vatRate)
}

private fun num(s: String): Double = s.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
private fun show(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

/** Create/edit an invoice: recipient, dates, currency, line items (live totals), note. */
@Composable
fun InvoiceEditScreen(inv: Invoice, vm: FinanceViewModel, onCancel: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf(inv.customer.name) }
    var attn by remember { mutableStateOf(inv.customer.attn) }
    var address by remember { mutableStateOf(inv.customer.address) }
    var email by remember { mutableStateOf(inv.customer.email) }
    var vatId by remember { mutableStateOf(inv.customer.vatId) }
    var issueDate by remember { mutableStateOf(inv.issueDate) }
    var dueDate by remember { mutableStateOf(inv.dueDate) }
    var currency by remember { mutableStateOf(inv.currency) }
    var note by remember { mutableStateOf(inv.note) }
    val lines = remember {
        inv.lines.map { LineDraft(it.desc, show(it.qty), it.unit, show(it.unitPrice), show(it.vatRate)) }
            .ifEmpty { listOf(LineDraft("", "1", "", "0", "19")) }.toMutableStateList()
    }

    fun build(): Invoice = inv.copy(
        issueDate = issueDate.trim(), dueDate = dueDate.trim(), currency = currency.trim().ifBlank { "EUR" },
        customer = inv.customer.copy(name = name.trim(), attn = attn.trim(), address = address.trim(), email = email.trim(), vatId = vatId.trim()),
        lines = lines.map { InvoiceLine(desc = it.desc.trim(), qty = num(it.qty), unit = it.unit.trim(), unitPrice = num(it.unitPrice), vatRate = num(it.vatRate)) },
        note = note.trim(),
        updated = java.time.Instant.now().toString(),
    )

    val liveTotals = de.ledgerline.app.core.finance.InvoiceMath.totals(build())

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(if (inv.number == null) R.string.finance_new else R.string.finance_edit),
                onBack = onCancel,
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Recipient
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.finance_recipient), style = MaterialTheme.typography.labelMedium, color = Brand.accent)
                Field(name, { name = it }, R.string.finance_customer_name)
                Field(attn, { attn = it }, R.string.finance_customer_attn)
                Field(address, { address = it }, R.string.finance_customer_address)
                Field(email, { email = it }, R.string.finance_customer_email)
                Field(vatId, { vatId = it }, R.string.finance_customer_vatid)
            }

            // Dates + currency
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(issueDate, { issueDate = it }, R.string.finance_issue_date)
                DateField(dueDate, { dueDate = it }, R.string.finance_due_date)
                Field(currency, { currency = it }, R.string.finance_currency)
            }

            // Line items
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.finance_items), style = MaterialTheme.typography.labelMedium, color = Brand.accent)
                lines.forEachIndexed { i, l ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            OutlinedTextField(l.desc, { l.desc = it }, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.finance_line_desc)) }, singleLine = true)
                            if (lines.size > 1) IconButton(onClick = { lines.remove(l) }) { Icon(Icons.Outlined.Close, stringResource(R.string.action_delete)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField(l.qty, { l.qty = it }, R.string.finance_line_qty, Modifier.weight(1f))
                            NumField(l.unitPrice, { l.unitPrice = it }, R.string.finance_line_price, Modifier.weight(1.3f))
                            NumField(l.vatRate, { l.vatRate = it }, R.string.finance_line_vat, Modifier.weight(1f))
                        }
                    }
                }
                TextButton(onClick = { lines.add(LineDraft("", "1", "", "0", "19")) }) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.finance_add_line))
                }
            }

            // Live totals
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.finance_gross), fontWeight = FontWeight.Bold)
                    Text(vm.money(liveTotals.gross, currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_note)) })

            PrimaryGradientButton(stringResource(R.string.action_save), onClick = { vm.save(build()) { ok -> if (ok) onSaved() } })
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, labelRes: Int) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(labelRes)) }, singleLine = true)
}

/** A read-only `YYYY-MM-DD` field that opens a Material date picker. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DateField(iso: String, onChange: (String) -> Unit, labelRes: Int) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = iso, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(labelRes)) },
        trailingIcon = { TextButton(onClick = { show = true }) { Text("…") } },
    )
    if (show) {
        val initial = runCatching {
            java.time.LocalDate.parse(iso).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initial)
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onChange(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString())
                    }
                    show = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { androidx.compose.material3.DatePicker(state = state) }
    }
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, labelRes: Int, modifier: Modifier) {
    OutlinedTextField(
        value, onChange, modifier = modifier, label = { Text(stringResource(labelRes)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
