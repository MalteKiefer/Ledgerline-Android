package de.ledgerline.app.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.finance.CategorySuggestion
import de.ledgerline.app.domain.model.finance.DuplicateGroup
import de.ledgerline.app.domain.model.finance.FinanceDuplicates
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.theme.cardSurface

/** Read-only insights: tax reports (VAT advance + EÜR) + duplicates + category suggestions. */
@Composable
fun InsightsScreen(vm: FinanceViewModel, onBack: (() -> Unit)? = null) {
    var dups by remember { mutableStateOf<FinanceDuplicates?>(null) }
    var suggestions by remember { mutableStateOf<List<CategorySuggestion>>(emptyList()) }
    var vatAdvance by remember { mutableStateOf<de.ledgerline.app.domain.model.finance.VatAdvanceReturn?>(null) }
    var euer by remember { mutableStateOf<de.ledgerline.app.domain.model.finance.EuerReport?>(null) }
    val year by vm.year.collectAsStateWithLifecycle()
    var quarter by remember { mutableStateOf<Int?>(null) } // null = full year
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh, year, quarter) {
        vatAdvance = vm.loadVatAdvance(year, quarter)
        euer = vm.loadEuer(year)
        dups = vm.loadDuplicates()
        suggestions = vm.loadSuggestions()
    }
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.more_insights), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Full year / quarter selector for the USt-Voranmeldung.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(selected = quarter == null, onClick = { quarter = null }, label = { Text(year.toString()) })
                (1..4).forEach { q ->
                    androidx.compose.material3.FilterChip(selected = quarter == q, onClick = { quarter = q }, label = { Text("Q$q") })
                }
            }
            vatAdvance?.let { v ->
                SectionLabel(stringResource(R.string.insights_vat_advance))
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (v.smallBusiness) Text(stringResource(R.string.insights_small_business), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReportRow(stringResource(R.string.insights_output_vat), FinanceViewModel.money(v.outputVat))
                    ReportRow(stringResource(R.string.insights_input_vat), FinanceViewModel.money(v.inputVat))
                    ReportRow(stringResource(R.string.insights_payable), FinanceViewModel.money(v.payable), bold = true)
                }
            }
            euer?.let { e ->
                SectionLabel(stringResource(R.string.insights_euer))
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReportRow(stringResource(R.string.insights_income), FinanceViewModel.money(e.income.total))
                    ReportRow(stringResource(R.string.insights_expenses), FinanceViewModel.money(e.expenses.total))
                    ReportRow(stringResource(R.string.insights_profit), FinanceViewModel.money(e.profit), bold = true)
                }
            }

            SectionLabel(stringResource(R.string.insights_suggestions))
            if (suggestions.isEmpty()) {
                Text(stringResource(R.string.insights_no_suggestions), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else suggestions.forEach { s ->
                Row(Modifier.fillMaxWidth().cardSurface(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.merchant, style = MaterialTheme.typography.bodyLarge)
                        Text(s.suggestedCategory, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.applySuggestion(s.txId, s.suggestedCategory) { if (it) refresh++ } }) {
                        Text(stringResource(R.string.insights_apply))
                    }
                }
            }

            SectionLabel(stringResource(R.string.insights_duplicate_invoices))
            DupGroups(dups?.invoices.orEmpty())
            SectionLabel(stringResource(R.string.insights_duplicate_transactions))
            DupGroups(dups?.transactions.orEmpty())
        }
    }
}

@Composable
private fun ReportRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
        Text(label, style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
        Text(value, style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DupGroups(groups: List<DuplicateGroup>) {
    if (groups.isEmpty()) {
        Text(stringResource(R.string.insights_no_duplicates), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    groups.forEach { g ->
        Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(g.reason, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.insights_ids) + " " + g.ids.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
