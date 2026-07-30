package de.ledgerline.app.ui.finance

import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import de.ledgerline.app.ui.common.SectionLabel
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
import androidx.compose.ui.draw.clip
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
    // open = the account whose bookings we're viewing; editForm = the account being edited (subpage).
    var open by remember { mutableStateOf<PaymentMethod?>(null) }
    var editForm by remember { mutableStateOf<PaymentMethod?>(null) }
    val ef = editForm
    val op = open
    when {
        ef != null -> PaymentMethodEditForm(ef, vm, onBack = { editForm = null })
        op != null -> PaymentMethodDetail(op, vm, onBack = { open = null }, onEdit = { editForm = op })
        else -> PaymentMethodList(vm, onBack = onBack, onEdit = { open = it }, onNew = { editForm = vm.newPaymentMethod() })
    }
}

@Composable
private fun PaymentMethodList(vm: FinanceViewModel, onBack: () -> Unit, onEdit: (PaymentMethod) -> Unit, onNew: () -> Unit) {
    vm.paymentMethods.collectAsStateWithLifecycle()   // recompose on change
    vm.financeScope.collectAsStateWithLifecycle()     // recompose on scope change
    val list = vm.scopedPaymentMethods()

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
        BankAvatar(pm, vm)
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

/** The bank/site logo (via /passwords/icon) when the account has a website, else the tinted glyph. */
@Composable
private fun BankAvatar(pm: PaymentMethod, vm: FinanceViewModel) {
    val icon by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pm.id, pm.url) {
        value = if (pm.url.isBlank()) null else vm.bankIconFor(pm)
    }
    val bmp = icon
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp, contentDescription = null,
            modifier = Modifier.size(38.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
        )
    } else {
        IconChip(icon = typeIcon(pm.type), tint = typeTint(pm.type))
    }
}

/** Bookings view for one payment account: balance + VAT summary + bookings; Edit via topbar menu. */
@Composable
private fun PaymentMethodDetail(pm: PaymentMethod, vm: FinanceViewModel, onBack: () -> Unit, onEdit: () -> Unit) {
    var txEditing by remember { mutableStateOf<de.ledgerline.app.domain.model.Transaction?>(null) }
    var egTx by remember { mutableStateOf<de.ledgerline.app.domain.model.Transaction?>(null) }
    val editingTx = txEditing
    val eg = egTx
    if (editingTx != null) { TransactionEditScreen(editingTx, vm, onBack = { txEditing = null }); return }
    if (eg != null) { EigenbelegScreen(eg, vm, onBack = { egTx = null }); return }

    var txQuery by remember { mutableStateOf("") }
    var txYear by remember { mutableStateOf(java.time.LocalDate.now().year.toString()) }
    vm.transactions.collectAsStateWithLifecycle()
    val allTxns = vm.accountTransactions(pm.id)
    val txYears = remember(allTxns) { (allTxns.mapNotNull { it.date.take(4).ifBlank { null } } + txYear).distinct().sortedDescending() }
    val txns = remember(allTxns, txQuery, txYear) {
        val q = txQuery.trim().lowercase()
        allTxns.filter { it.date.startsWith(txYear) }.filter {
            q.isEmpty() || it.counterparty.lowercase().contains(q) || it.purpose.lowercase().contains(q) || it.date.contains(q) ||
                de.ledgerline.app.core.finance.AmountSearch.amountMatches(it.amount, txQuery)
        }
    }
    val vat = remember(allTxns, txYear) { vm.accountVatFor(pm.id, txYear) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val launchImport = rememberStatementImport(vm, pm.id) { added, matched ->
        val msg = when {
            added > 0 && matched > 0 -> ctx.getString(R.string.finance_import_added_matched, added, matched)
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
                title = pm.label.ifBlank { typeLabel(pm.type) },
                onBack = onBack,
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.finance_edit)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.finance_import_action)) }, leadingIcon = { Icon(Icons.Outlined.FileOpen, null) }, onClick = { menu = false; launchImport() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.finance_tx_add)) }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = { menu = false; txEditing = vm.newTransaction(pm.id) })
                        androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; vm.trashPaymentMethod(pm) { if (it) onBack() } })
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Account header: avatar, subtitle, balance, business badge.
            Row(Modifier.fillMaxWidth().cardSurface(), verticalAlignment = Alignment.CenterVertically) {
                BankAvatar(pm, vm)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(pm.label.ifBlank { typeLabel(pm.type) }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    val sub = PaymentMethods.subtitle(pm)
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    if (pm.business) Text(stringResource(R.string.finance_pm_business), style = MaterialTheme.typography.labelSmall, color = Brand.accent)
                }
                if (allTxns.isNotEmpty()) Text(vm.money(vm.accountBalance(pm.id), null), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            // Year filter for both bookings + VAT.
            if (txYears.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    txYears.forEach { y -> FilterChip(selected = y == txYear, onClick = { txYear = y }, label = { Text(y) }) }
                }
            }

            // VAT summary (Umsatzsteuer) for this account + year.
            if (vat.outputVat != 0.0 || vat.inputVat != 0.0 || vat.undecided > 0) {
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel(stringResource(R.string.finance_stats_vat_payable_title))
                    VatRow(stringResource(R.string.finance_stats_output_vat), vm.money(vat.outputVat, null))
                    VatRow(stringResource(R.string.finance_stats_input_vat), vm.money(vat.inputVat, null))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    VatRow(stringResource(R.string.finance_stats_vat_payable), vm.money(vat.payable, null), bold = true)
                    if (vat.undecided > 0) Text(
                        stringResource(R.string.finance_stats_vat_undecided, vat.undecided),
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2915A),
                    )
                }
            }

            // Bookings.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.finance_pm_bookings_title), Modifier.weight(1f))
            }
            if (allTxns.size > 6) {
                OutlinedTextField(txQuery, { txQuery = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_tx_search)) }, singleLine = true)
            }
            if (txns.isEmpty()) {
                Text(stringResource(R.string.finance_tx_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            } else {
                Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
                    txns.take(200).forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(Modifier.fillMaxWidth().clickable { txEditing = t }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.counterparty.ifBlank { t.purpose.ifBlank { "—" } }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(t.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    VatCatBadge(t.vatCat)
                                    when {
                                        vm.hasEigenbeleg(t) -> EgBadge(stringResource(R.string.finance_eg_badge), Brand.tintGreen)
                                        vm.needsEigenbeleg(t) -> EgBadge(stringResource(R.string.finance_eg_missing_badge), Color(0xFFE2915A))
                                    }
                                }
                            }
                            // Booking with no receipt → quick Eigenbeleg action.
                            if (vm.receiptsOf(t).isEmpty()) IconButton(onClick = { egTx = t }) {
                                Icon(Icons.Outlined.NoteAdd, contentDescription = stringResource(R.string.finance_eg_title))
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

@Composable
private fun VatRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

/** A small VAT-category chip on a booking row (19%/7%/private/…); nothing for undecided. */
@Composable
private fun VatCatBadge(cat: String) {
    val label = when (cat) {
        "private" -> stringResource(R.string.finance_tx_vat_private)
        "0" -> "0%"
        "" -> return
        else -> "$cat%"
    }
    Spacer(Modifier.size(6.dp))
    androidx.compose.material3.Surface(
        color = Brand.accent.copy(alpha = 0.12f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.accent, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/** A small pill for a booking's voucher status (has / needs an Eigenbeleg). */
@Composable
private fun EgBadge(label: String, tint: Color) {
    Spacer(Modifier.size(6.dp))
    androidx.compose.material3.Surface(color = tint.copy(alpha = 0.14f), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/** The payment-method edit form (fields + business toggle); Save via the topbar. */
@Composable
private fun PaymentMethodEditForm(initial: PaymentMethod, vm: FinanceViewModel, onBack: () -> Unit) {
    var pm by remember(initial) { mutableStateOf(initial) }
    val exists = vm.paymentMethodById(initial.id) != null
    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(if (exists) R.string.finance_pm_edit else R.string.finance_pm_add),
                onBack = onBack,
                actions = {
                    androidx.compose.material3.TextButton(
                        onClick = { vm.savePaymentMethod(pm.trimmed()) { if (it) onBack() } },
                        enabled = PaymentMethods.isValid(pm.copy(label = pm.label.trim())),
                    ) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentMethods.TYPES.forEach { t ->
                    FilterChip(selected = pm.type == t, onClick = { pm = pm.copy(type = t) }, label = { Text(typeLabel(t)) })
                }
            }
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.finance_partner_details))
                Field(pm.label, { pm = pm.copy(label = it) }, R.string.finance_pm_label)
                Field(pm.holder, { pm = pm.copy(holder = it) }, R.string.finance_pm_holder)
                when (pm.type) {
                    "bank" -> {
                        Field(pm.iban, { pm = pm.copy(iban = it) }, R.string.finance_pm_iban)
                        Field(pm.bic, { pm = pm.copy(bic = it) }, R.string.finance_company_bic)
                        Field(pm.bankName, { pm = pm.copy(bankName = it) }, R.string.finance_company_bank)
                        Field(pm.accountNumber, { pm = pm.copy(accountNumber = it) }, R.string.finance_pm_account_no)
                        Field(pm.url, { pm = pm.copy(url = it) }, R.string.finance_pm_url)
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
            Row(Modifier.fillMaxWidth().cardSurface(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.finance_pm_business), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.Switch(checked = pm.business, onCheckedChange = { pm = pm.copy(business = it) })
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
