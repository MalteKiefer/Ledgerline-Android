package de.ledgerline.app.ui.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.Partner
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.PrimaryGradientButton
import de.ledgerline.app.ui.theme.cardSurface

/** Business-partners (merchants/clients) manager over the store's `partners` collection (partRef). */
@Composable
fun PartnersScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<Partner?>(null) }
    val e = editing
    if (e != null) { PartnerEdit(e, vm, onBack = { editing = null }); return }

    vm.partners.collectAsStateWithLifecycle()
    val list = vm.sortedPartners()
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.finance_partners), onBack = onBack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                if (list.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.finance_partners_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
                        list.forEachIndexed { i, p ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Row(p, onClick = { editing = p })
                        }
                    }
                }
            }
            FloatingActionButton(onClick = { editing = vm.newPartner() }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                Icon(Icons.Outlined.Add, stringResource(R.string.finance_partner_new))
            }
        }
    }
}

@Composable
private fun Row(p: Partner, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        de.ledgerline.app.ui.theme.IconChip(androidx.compose.material.icons.Icons.Outlined.Groups, tint = de.ledgerline.app.ui.theme.Brand.tintBlue, size = 34.dp)
        androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            if (p.category.isNotBlank()) Text(p.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun PartnerEdit(initial: Partner, vm: FinanceViewModel, onBack: () -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var category by remember(initial) { mutableStateOf(initial.category) }
    var note by remember(initial) { mutableStateOf(initial.note) }
    val exists = vm.partnerById(initial.id) != null
    AppScaffold(topBar = {
        AppTopBar(title = stringResource(if (exists) R.string.finance_partner_edit else R.string.finance_partner_new), onBack = onBack, actions = {
            if (exists) IconButton(onClick = { vm.deletePartner(initial) { if (it) onBack() } }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_partner_name)) }, singleLine = true)
                OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_partner_category)) }, singleLine = true)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_pm_note)) })
            }
            PrimaryGradientButton(stringResource(R.string.action_save), enabled = name.isNotBlank(), onClick = {
                vm.savePartner(initial.copy(name = name.trim(), category = category.trim(), note = note.trim())) { if (it) onBack() }
            })
        }
    }
}
