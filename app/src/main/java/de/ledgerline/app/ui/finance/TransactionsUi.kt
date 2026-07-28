package de.ledgerline.app.ui.finance

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.core.finance.BankStatement
import de.ledgerline.app.domain.model.Transaction
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.PrimaryGradientButton
import de.ledgerline.app.ui.theme.cardSurface

/** Manually add/edit a single booking. */
@Composable
fun TransactionEditScreen(initial: Transaction, vm: FinanceViewModel, onBack: () -> Unit) {
    var date by remember(initial) { mutableStateOf(initial.date) }
    var amount by remember(initial) { mutableStateOf(if (initial.amount == 0.0) "" else trimAmount(initial.amount)) }
    var counterparty by remember(initial) { mutableStateOf(initial.counterparty) }
    var purpose by remember(initial) { mutableStateOf(initial.purpose) }
    var vatCat by remember(initial) { mutableStateOf(initial.vatCat) }
    val exists = vm.transactions.value.any { it.id == initial.id }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(if (exists) R.string.finance_tx_edit else R.string.finance_tx_add),
                onBack = onBack,
                actions = {
                    if (exists) IconButton(onClick = { vm.trashTransaction(initial) { if (it) onBack() } }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete))
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_date)) }, singleLine = true)
                OutlinedTextField(
                    amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_tx_amount)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(counterparty, { counterparty = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_tx_counterparty)) }, singleLine = true)
                OutlinedTextField(purpose, { purpose = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_tx_purpose)) }, singleLine = true)
            }
            // VAT category chips
            Text(stringResource(R.string.finance_tx_vatcat), style = MaterialTheme.typography.labelMedium, color = Brand.accent)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("") + BankStatement.VAT_CATS).forEach { c ->
                    FilterChip(selected = vatCat == c, onClick = { vatCat = c }, label = { Text(vatCatLabel(c)) })
                }
            }
            PrimaryGradientButton(
                stringResource(R.string.action_save),
                enabled = date.isNotBlank() && amount.replace(',', '.').toDoubleOrNull() != null,
                onClick = {
                    vm.saveTransaction(
                        initial.copy(
                            date = date.trim(), amount = amount.replace(',', '.').trim().toDoubleOrNull() ?: 0.0,
                            counterparty = counterparty.trim(), purpose = purpose.trim(), vatCat = vatCat,
                        ),
                    ) { if (it) onBack() }
                },
            )
        }
    }
}

@Composable
private fun vatCatLabel(c: String): String = when (c) {
    "" -> stringResource(R.string.finance_tx_vat_none)
    "private" -> stringResource(R.string.finance_tx_vat_private)
    else -> "$c %"
}

private fun trimAmount(d: Double): String = if (d == kotlin.math.floor(d)) d.toLong().toString() else d.toString()

// ---- statement import ----

/**
 * A button that launches a file picker and imports a bank statement into [accountId]. Auto-detects
 * MT940 + known CSVs; an unknown CSV opens a column-mapping dialog. Deduped by the VM. Reports the
 * outcome via [onResult] (count added / 0 duplicates / -1 failed / -2 unparseable).
 */
@Composable
fun rememberStatementImport(vm: FinanceViewModel, accountId: String, onResult: (added: Int, matched: Int) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var mapping by remember { mutableStateOf<PendingCsv?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val name = uri.lastPathSegment ?: ""
        if (text.isNullOrBlank()) { onResult(-2, 0); return@rememberLauncherForActivityResult }
        when (BankStatement.detectFormat(text, name)) {
            "mt940" -> vm.importTransactions(BankStatement.parseMt940(text).transactions, accountId, onDone = onResult)
            "csv" -> {
                val csv = BankStatement.parseCsv(text)
                val auto = BankStatement.detectCsvMapping(csv.header)
                if (auto != null) {
                    val applied = BankStatement.applyCsvMapping(csv.header, csv.rows, auto.map)
                    vm.importTransactions(applied.transactions, accountId, onDone = onResult)
                } else {
                    mapping = PendingCsv(csv)   // ask the user to map columns
                }
            }
            else -> onResult(-2, 0)
        }
    }

    mapping?.let { pending ->
        CsvMappingDialog(
            pending.csv,
            onCancel = { mapping = null },
            onApply = { map ->
                mapping = null
                val applied = BankStatement.applyCsvMapping(pending.csv.header, pending.csv.rows, map)
                vm.importTransactions(applied.transactions, accountId, onDone = onResult)
            },
        )
    }

    return { launcher.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*")) }
}

private data class PendingCsv(val csv: BankStatement.Csv)

/** Minimal column-mapping dialog for an unrecognised CSV: pick the column for each target field. */
@Composable
private fun CsvMappingDialog(csv: BankStatement.Csv, onCancel: () -> Unit, onApply: (Map<String, String>) -> Unit) {
    val selection = remember { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.finance_import_map_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.finance_import_map_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BankStatement.TX_FIELDS.forEach { field ->
                    ColumnPicker(field, csv.header, selection[field]) { col -> if (col == null) selection.remove(field) else selection[field] = col }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selection.containsKey("date") && selection.containsKey("amount"),
                onClick = { onApply(selection.toMap()) },
            ) { Text(stringResource(R.string.finance_import_apply)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ColumnPicker(field: String, header: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val required = field in BankStatement.TX_REQUIRED
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            field + if (required) " *" else "",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (required) FontWeight.SemiBold else FontWeight.Normal,
        )
        Box {
            TextButton(onClick = { open = true }) { Text(selected ?: "—", maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("—") }, onClick = { onSelect(null); open = false })
                header.forEach { col ->
                    DropdownMenuItem(text = { Text(col) }, onClick = { onSelect(col); open = false })
                }
            }
        }
    }
}
