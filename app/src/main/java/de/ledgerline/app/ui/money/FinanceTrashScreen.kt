package de.ledgerline.app.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.finance.FinanceTrash
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.LedgerRow
import de.ledgerline.app.ui.common.ListBottomPadding
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.common.listSection

/** Finance trash: soft-deleted invoices/transactions/partners/payment-methods/projects with
 *  restore + permanent-delete per row. */
@Composable
fun TrashTab(vm: FinanceViewModel) {
    var trash by remember { mutableStateOf<FinanceTrash?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { trash = vm.loadTrash() }
    val t = trash

    run {
        val empty = t == null || (t.invoices.isEmpty() && t.transactions.isEmpty() && t.partners.isEmpty() && t.paymentMethods.isEmpty() && t.projects.isEmpty() && t.standaloneReceipts.isEmpty())
        Box(Modifier.fillMaxSize()) {
            if (empty) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.files_trash_is_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                section(R.string.tab_invoices, t!!.invoices.map { it.id to (it.number ?: it.issueDate ?: "#${it.id}") },
                    onRestore = { id -> vm.restoreInvoice(id) { reload++ } }, onForce = { id -> vm.forceInvoice(id) { reload++ } })
                section(R.string.tab_transactions, t.transactions.map { it.id to (it.counterparty ?: it.purpose ?: it.date) },
                    onRestore = { id -> vm.restoreTransaction(id) { reload++ } }, onForce = { id -> vm.forceTransaction(id) { reload++ } })
                section(R.string.more_partners, t.partners.map { it.id to it.name },
                    onRestore = { id -> vm.restorePartner(id) { reload++ } }, onForce = { id -> vm.forcePartner(id) { reload++ } })
                section(R.string.more_payment_methods, t.paymentMethods.map { it.id to it.name },
                    onRestore = { id -> vm.restorePaymentMethod(id) { reload++ } }, onForce = { id -> vm.forcePaymentMethod(id) { reload++ } })
                section(R.string.more_projects, t.projects.map { it.id to it.name },
                    onRestore = { id -> vm.restoreProject(id) { reload++ } }, onForce = { id -> vm.forceProject(id) { reload++ } })
                section(R.string.more_receipts, t.standaloneReceipts.map { it.id to it.name },
                    onRestore = { id -> vm.restoreStandaloneReceipt(id) { reload++ } }, onForce = { id -> vm.forceStandaloneReceipt(id) { reload++ } })
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    labelRes: Int,
    rows: List<Pair<Int, String>>,
    onRestore: (Int) -> Unit,
    onForce: (Int) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "h$labelRes") { SectionLabel(stringResource(labelRes)) }
    listSection(rows, key = { "r$labelRes-${it.first}" }) { (id, title) ->
        LedgerRow(
            title = title.ifBlank { "#$id" },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onRestore(id) }) { Text(stringResource(R.string.action_restore)) }
                    IconButton(onClick = { onForce(id) }) {
                        Icon(Icons.Outlined.DeleteForever, contentDescription = stringResource(R.string.action_delete_forever), tint = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }
}
