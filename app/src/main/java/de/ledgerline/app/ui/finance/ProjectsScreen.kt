package de.ledgerline.app.ui.finance

import de.ledgerline.app.ui.common.SectionLabel
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.Project
import de.ledgerline.app.domain.model.ProjectExpense
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.PrimaryGradientButton
import de.ledgerline.app.ui.theme.cardSurface

/** Cost-projects manager: nestable tree with rolled totals → detail (expenses, sub-projects). */
@Composable
fun ProjectsScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    var openId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Project?>(null) }
    val editP = editing
    val open = openId?.let { vm.projectById(it) }
    when {
        editP != null -> ProjectEditScreen(editP, vm, onBack = { editing = null })
        open != null -> ProjectDetail(open, vm, onBack = { openId = null }, onEdit = { editing = open }, onOpenChild = { openId = it }, onNewChild = { editing = vm.newProject(open.id, open.kind) })
        else -> ProjectTree(vm, onBack = onBack, onOpen = { openId = it }, onNew = { editing = vm.newProject(null) })
    }
}

@Composable
private fun ProjectTree(vm: FinanceViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit) {
    vm.projects.collectAsStateWithLifecycle()
    vm.transactions.collectAsStateWithLifecycle()
    val scope by vm.financeScope.collectAsStateWithLifecycle()
    val rows = vm.scopedProjectTree()
    val (bizTotal, privTotal) = vm.projectScopeTotals()
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.finance_projects), onBack = onBack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.size(4.dp))
                // Cost split business vs private (own totals; no tree double-counting).
                if (bizTotal != 0.0 || privTotal != 0.0) {
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().cardSurface(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(vm.money2(bizTotal), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = de.ledgerline.app.ui.theme.Brand.accent); Text(stringResource(R.string.finance_project_business), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Column(horizontalAlignment = Alignment.End) { Text(vm.money2(privTotal), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(stringResource(R.string.finance_project_private), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.finance_projects_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
                        rows.forEachIndexed { i, row ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = (16 + row.depth * 16).dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpen(row.project.id) }
                                    .padding(start = (16 + row.depth * 16).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.project.name.ifBlank { stringResource(R.string.finance_project_untitled) }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(kindLabel(row.project.kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(vm.money2(vm.projectRolledTotal(row.project.id)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                }
            }
            FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                Icon(Icons.Outlined.Add, stringResource(R.string.finance_project_new))
            }
        }
    }
}

@Composable
private fun ProjectDetail(p: Project, vm: FinanceViewModel, onBack: () -> Unit, onEdit: () -> Unit, onOpenChild: (String) -> Unit, onNewChild: () -> Unit) {
    vm.projects.collectAsStateWithLifecycle()
    vm.transactions.collectAsStateWithLifecycle()
    val children = de.ledgerline.app.core.finance.FinanceProjects.projectChildren(vm.projects.value, p.id)
    val receipts = de.ledgerline.app.core.finance.FinanceProjects.projectReceipts(vm.allReceipts(), p.id)
    var expenseEdit by remember { mutableStateOf<ProjectExpense?>(null) }
    val ee = expenseEdit
    if (ee != null) { ExpenseEditScreen(p, ee, vm, onBack = { expenseEdit = null }); return }

    AppScaffold(
        topBar = {
            AppTopBar(title = p.name.ifBlank { stringResource(R.string.finance_project_untitled) }, onBack = onBack, actions = {
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.finance_edit)) }
                IconButton(onClick = { vm.deleteProject(p) { if (it) onBack() } }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
            })
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Totals
            Row(Modifier.fillMaxWidth().cardSurface(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text(vm.money2(vm.projectRolledTotal(p.id)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Brand.accent); Text(stringResource(R.string.finance_project_total_rolled), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Column(horizontalAlignment = Alignment.End) { Text(vm.money2(vm.projectOwnTotal(p)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(stringResource(R.string.finance_project_total_own), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (p.note.isNotBlank()) Text(p.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Manual expenses
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.finance_project_expenses), Modifier.weight(1f))
                TextButton(onClick = { expenseEdit = ProjectExpense(id = de.ledgerline.app.core.Ids.newId(), date = java.time.LocalDate.now().toString()) }) { Text(stringResource(R.string.finance_tx_add)) }
            }
            if (p.expenses.isNotEmpty()) Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
                p.expenses.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(Modifier.fillMaxWidth().clickable { expenseEdit = e }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(e.note.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1); Text(e.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(vm.money2(e.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Bundled receipts (read-only)
            if (receipts.isNotEmpty()) {
                SectionLabel(stringResource(R.string.finance_project_receipts))
                Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
                    receipts.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.receipt.name.ifBlank { r.tx.counterparty.ifBlank { "—" } }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(vm.money2(de.ledgerline.app.core.finance.FinanceProjects.receiptAmount(r.receipt, r.tx)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // Sub-projects
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.finance_project_subprojects), Modifier.weight(1f))
                TextButton(onClick = onNewChild) { Text(stringResource(R.string.finance_project_new)) }
            }
            if (children.isNotEmpty()) Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
                children.sortedBy { it.name.lowercase() }.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(Modifier.fillMaxWidth().clickable { onOpenChild(c.id) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name.ifBlank { stringResource(R.string.finance_project_untitled) }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(vm.money2(vm.projectRolledTotal(c.id)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectEditScreen(initial: Project, vm: FinanceViewModel, onBack: () -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var note by remember(initial) { mutableStateOf(initial.note) }
    var kind by remember(initial) { mutableStateOf(initial.kind) }
    AppScaffold(topBar = { AppTopBar(title = stringResource(if (vm.projectById(initial.id) != null) R.string.finance_project_edit else R.string.finance_project_new), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_project_name)) }, singleLine = true)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_pm_note)) })
            }
            if (initial.parentId != null) {
                // A sub-project inherits its root's kind — locked (web parity).
                Text(
                    stringResource(R.string.finance_project_kind_inherited) + " (" + kindLabel(vm.effectiveKind(initial.parentId!!)) + ")",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("business", "private").forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(kindLabel(k)) })
                    }
                }
            }
            PrimaryGradientButton(stringResource(R.string.action_save), enabled = name.isNotBlank(), onClick = {
                vm.saveProject(initial.copy(name = name.trim(), note = note.trim(), kind = kind)) { if (it) onBack() }
            })
        }
    }
}

@Composable
private fun ExpenseEditScreen(project: Project, initial: ProjectExpense, vm: FinanceViewModel, onBack: () -> Unit) {
    var amount by remember(initial) { mutableStateOf(if (initial.amount == 0.0) "" else initial.amount.toString()) }
    var date by remember(initial) { mutableStateOf(initial.date) }
    var note by remember(initial) { mutableStateOf(initial.note) }
    var category by remember(initial) { mutableStateOf(initial.category) }
    val exists = project.expenses.any { it.id == initial.id }
    AppScaffold(topBar = { AppTopBar(title = stringResource(if (exists) R.string.finance_tx_edit else R.string.finance_tx_add), onBack = onBack, actions = {
        if (exists) IconButton(onClick = { vm.saveProject(project.copy(expenses = project.expenses.filter { it.id != initial.id })) { if (it) onBack() } }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
    }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_tx_amount)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_date)) }, singleLine = true)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_pm_note)) }, singleLine = true)
                OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_expense_category)) }, singleLine = true)
            }
            PrimaryGradientButton(stringResource(R.string.action_save), enabled = amount.replace(',', '.').toDoubleOrNull() != null, onClick = {
                val e = initial.copy(amount = amount.replace(',', '.').trim().toDoubleOrNull() ?: 0.0, date = date.trim(), note = note.trim(), category = category.trim())
                val next = if (exists) project.expenses.map { if (it.id == e.id) e else it } else project.expenses + e
                vm.saveProject(project.copy(expenses = next)) { if (it) onBack() }
            })
        }
    }
}

@Composable
private fun kindLabel(kind: String): String = stringResource(if (kind == "private") R.string.finance_project_private else R.string.finance_project_business)
