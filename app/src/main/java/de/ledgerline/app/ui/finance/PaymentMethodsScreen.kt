package de.ledgerline.app.ui.finance

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.core.finance.PaymentMethods
import de.ledgerline.app.domain.model.PaymentMethod
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.IconChip
import de.ledgerline.app.ui.theme.cardSurface

private fun typeTint(type: String): Color = when (type) {
    "bank" -> Color(0xFF3B9FD6)
    "card" -> Color(0xFF7066F5)
    "paypal" -> Color(0xFF3FAE9F)
    "cash" -> Color(0xFF59AD6B)
    else -> Color(0xFF6B7280)
}

private fun typeIcon(type: String): ImageVector = when (type) {
    "bank" -> Icons.Outlined.AccountBalance
    "card" -> Icons.Outlined.CreditCard
    "paypal" -> Icons.Outlined.Language
    "cash" -> Icons.Outlined.Payments
    else -> Icons.Outlined.AccountBalanceWallet
}

@Composable
private fun typeLabel(type: String): String = stringResource(
    when (type) {
        "bank" -> R.string.finance_pm_type_bank
        "card" -> R.string.finance_pm_type_card
        "paypal" -> R.string.finance_pm_type_paypal
        "cash" -> R.string.finance_pm_type_cash
        else -> R.string.finance_pm_type_other
    },
)

/** Payment-methods manager: list of accounts (with balance) → add/edit editor. */
@Composable
fun PaymentMethodsScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<PaymentMethod?>(null) }
    val target = editing
    if (target != null) {
        PaymentMethodEditScreen(target, vm, onBack = { editing = null })
    } else {
        PaymentMethodList(vm, onBack = onBack, onEdit = { editing = it }, onNew = { editing = vm.newPaymentMethod() })
    }
}

@Composable
private fun PaymentMethodList(vm: FinanceViewModel, onBack: () -> Unit, onEdit: (PaymentMethod) -> Unit, onNew: () -> Unit) {
    vm.paymentMethods.collectAsStateWithLifecycle()   // recompose on change
    val list = vm.sortedPaymentMethods()

    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.finance_pm_title), onBack = onBack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.size(2.dp))
                if (list.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.finance_pm_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
                        list.forEachIndexed { i, pm ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            PaymentRow(pm, vm, onClick = { onEdit(pm) })
                        }
                    }
                }
            }
            FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                Icon(Icons.Outlined.Add, stringResource(R.string.finance_pm_add))
            }
        }
    }
}

@Composable
private fun PaymentRow(pm: PaymentMethod, vm: FinanceViewModel, onClick: () -> Unit) {
    val txns = vm.accountTransactions(pm.id)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(icon = typeIcon(pm.type), tint = typeTint(pm.type))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(pm.label.ifBlank { typeLabel(pm.type) }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            val sub = PaymentMethods.subtitle(pm)
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (txns.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.End) {
                Text(vm.money(vm.accountBalance(pm.id), pm.currencyOrNull()), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(stringResource(R.string.finance_pm_bookings, txns.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun PaymentMethod.currencyOrNull(): String? = null   // accounts have no own currency; use company default

@Composable
private fun PaymentMethodEditScreen(initial: PaymentMethod, vm: FinanceViewModel, onBack: () -> Unit) {
    var txEditing by remember { mutableStateOf<de.ledgerline.app.domain.model.Transaction?>(null) }
    val editingTx = txEditing
    if (editingTx != null) {
        TransactionEditScreen(editingTx, vm, onBack = { txEditing = null })
        return
    }
    PaymentMethodEditBody(initial, vm, onBack = onBack, onEditTx = { txEditing = it })
}

@Composable
private fun PaymentMethodEditBody(initial: PaymentMethod, vm: FinanceViewModel, onBack: () -> Unit, onEditTx: (de.ledgerline.app.domain.model.Transaction) -> Unit) {
    var pm by remember(initial) { mutableStateOf(initial) }
    vm.transactions.collectAsStateWithLifecycle()   // recompose after import/edit
    val txns = vm.accountTransactions(initial.id)
    val exists = vm.paymentMethodById(initial.id) != null
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val launchImport = rememberStatementImport(vm, initial.id) { added ->
        val msg = when {
            added > 0 -> ctx.getString(R.string.finance_import_added, added)
            added == 0 -> ctx.getString(R.string.finance_import_none)
            added == -2 -> ctx.getString(R.string.finance_import_unreadable)
            else -> ctx.getString(R.string.finance_import_failed)
        }
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(if (exists) R.string.finance_pm_edit else R.string.finance_pm_add),
                onBack = onBack,
                actions = {
                    if (exists) {
                        IconButton(onClick = { vm.trashPaymentMethod(pm) { if (it) onBack() } }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete))
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Type picker
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentMethods.TYPES.forEach { t ->
                    FilterChip(selected = pm.type == t, onClick = { pm = pm.copy(type = t) }, label = { Text(typeLabel(t)) })
                }
            }

            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(pm.label, { pm = pm.copy(label = it) }, R.string.finance_pm_label)
                Field(pm.holder, { pm = pm.copy(holder = it) }, R.string.finance_pm_holder)
                when (pm.type) {
                    "bank" -> {
                        Field(pm.iban, { pm = pm.copy(iban = it) }, R.string.finance_pm_iban)
                        Field(pm.bic, { pm = pm.copy(bic = it) }, R.string.finance_company_bic)
                        Field(pm.bankName, { pm = pm.copy(bankName = it) }, R.string.finance_company_bank)
                        Field(pm.accountNumber, { pm = pm.copy(accountNumber = it) }, R.string.finance_pm_account_no)
                    }
                    "card" -> {
                        Field(pm.cardNumber, { pm = pm.copy(cardNumber = it, cardNetwork = PaymentMethods.cardNetworkOf(it)) }, R.string.finance_pm_card_no, number = true)
                        Field(pm.cardExpiry, { pm = pm.copy(cardExpiry = it) }, R.string.finance_pm_card_exp)
                    }
                    "paypal" -> {
                        Field(pm.email, { pm = pm.copy(email = it) }, R.string.finance_customer_email)
                        Field(pm.url, { pm = pm.copy(url = it) }, R.string.finance_pm_url)
                    }
                    else -> {}
                }
                Field(pm.note, { pm = pm.copy(note = it) }, R.string.finance_pm_note)
            }

            de.ledgerline.app.ui.theme.PrimaryGradientButton(
                stringResource(R.string.action_save),
                enabled = PaymentMethods.isValid(pm.copy(label = pm.label.trim())),
                onClick = { vm.savePaymentMethod(pm.trimmed()) { if (it) onBack() } },
            )

            // Bookings for this account: import a statement, add manually, edit each.
            if (exists) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.finance_pm_bookings_title), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Brand.accent)
                    androidx.compose.material3.TextButton(onClick = launchImport) { Text(stringResource(R.string.finance_import_action)) }
                    androidx.compose.material3.TextButton(onClick = { onEditTx(vm.newTransaction(initial.id)) }) { Text(stringResource(R.string.finance_tx_add)) }
                }
            }
            if (txns.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
                    txns.take(200).forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(Modifier.fillMaxWidth().clickable { onEditTx(t) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.counterparty.ifBlank { t.purpose.ifBlank { "—" } }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(t.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                vm.money(t.amount, t.currency),
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1,
                                color = if (t.amount < 0) Color(0xFFE2915A) else Color(0xFF59AD6B),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

private fun PaymentMethod.trimmed(): PaymentMethod = copy(
    label = label.trim(), holder = holder.trim(), iban = iban.trim(), bic = bic.trim(),
    bankName = bankName.trim(), accountNumber = accountNumber.trim(), url = url.trim(),
    cardNumber = cardNumber.trim(), cardExpiry = cardExpiry.trim(), email = email.trim(), note = note.trim(),
)

@Composable
private fun Field(value: String, onChange: (String) -> Unit, labelRes: Int, number: Boolean = false) {
    OutlinedTextField(
        value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(labelRes)) }, singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    )
}
