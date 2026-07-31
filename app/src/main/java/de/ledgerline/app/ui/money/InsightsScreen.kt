package de.ledgerline.app.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.finance.CategorySuggestion
import de.ledgerline.app.domain.model.finance.DuplicateGroup
import de.ledgerline.app.domain.model.finance.FinanceDuplicates
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.theme.cardSurface

/** Read-only insights: server-detected duplicate groups + merchant→category suggestions (applyable). */
@Composable
fun InsightsScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    var dups by remember { mutableStateOf<FinanceDuplicates?>(null) }
    var suggestions by remember { mutableStateOf<List<CategorySuggestion>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        dups = vm.loadDuplicates()
        suggestions = vm.loadSuggestions()
    }
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.more_insights), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
