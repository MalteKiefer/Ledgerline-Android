package de.ledgerline.app.ui.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.finance.CompanyProfile
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.DocOpener
import de.ledgerline.app.ui.common.SectionLabel
import kotlinx.coroutines.launch
import de.ledgerline.app.ui.theme.cardSurface
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ===========================================================================
//  Invoices
// ===========================================================================
@Composable
fun InvoicesTab(vm: FinanceViewModel, onEdit: (Int?) -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        val invoices = data?.invoices?.filter { it.deletedAt == null }?.sortedByDescending { it.issueDate ?: "" }.orEmpty()
        if (invoices.isEmpty()) {
            EmptyState(stringResource(R.string.invoices_empty))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(invoices, key = { it.id }) { inv ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onEdit(inv.id) }.cardSurface(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(inv.number ?: stringResource(R.string.invoice_draft), style = MaterialTheme.typography.titleMedium)
                            Text(FinanceViewModel.money(inv.gross, inv.currency), style = MaterialTheme.typography.titleMedium)
                        }
                        val importedTag = if (inv.imported) stringResource(R.string.invoice_imported) else null
                        Text(
                            listOfNotNull(customerName(inv), inv.issueDate, statusLabel(inv.status), importedTag).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { onEdit(null) },
            icon = { Icon(Icons.Outlined.Add, null) },
            text = { Text(stringResource(R.string.invoice_new)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

private fun customerName(inv: de.ledgerline.app.domain.model.finance.Invoice): String? =
    (inv.customer?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content

@Composable
private fun statusLabel(status: String): String = stringResource(
    when (status) {
        "paid" -> R.string.invoice_status_paid
        "sent" -> R.string.invoice_status_sent
        else -> R.string.invoice_status_draft
    },
)

private class LineRow(desc: String, qty: String, price: String, vat: String) {
    var desc by androidx.compose.runtime.mutableStateOf(desc)
    var qty by androidx.compose.runtime.mutableStateOf(qty)
    var price by androidx.compose.runtime.mutableStateOf(price)
    var vat by androidx.compose.runtime.mutableStateOf(vat)
    val net: Double get() = (qty.replace(',', '.').toDoubleOrNull() ?: 0.0) * (price.replace(',', '.').toDoubleOrNull() ?: 0.0)
    val vatAmount: Double get() = net * (vat.replace(',', '.').toDoubleOrNull() ?: 0.0) / 100.0
}

private fun jstr(o: JsonObject?, k: String): String =
    (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

@Composable
fun InvoiceEditScreen(vm: FinanceViewModel, id: Int?, onBack: () -> Unit) {
    val existing = remember(id) { id?.let { vm.invoice(it) } }
    // Imported invoices are an immutable record of the original PDF (web parity) — show the key
    // fields read-only + open the original PDF, no editor.
    if (existing != null && existing.imported) {
        ImportedInvoiceScreen(vm, existing, onBack)
        return
    }
    val defaultVat = "19"
    var customer by remember { mutableStateOf(existing?.let { customerName(it) } ?: "") }
    var issueDate by remember { mutableStateOf(existing?.issueDate ?: "") }
    var dueDate by remember { mutableStateOf(existing?.dueDate ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    val lines = remember {
        androidx.compose.runtime.mutableStateListOf<LineRow>().apply {
            val src = existing?.lines.orEmpty()
            if (src.isEmpty()) add(LineRow("", "1", "", defaultVat))
            else src.forEach { l -> add(LineRow(jstr(l, "desc"), jstr(l, "qty").ifBlank { "1" }, jstr(l, "unitPrice"), jstr(l, "vatRate").ifBlank { defaultVat })) }
        }
    }
    var busy by remember { mutableStateOf(false) }

    val net = lines.sumOf { it.net }
    val vat = lines.sumOf { it.vatAmount }
    val gross = net + vat

    fun body(): JsonObject = buildJsonObject {
        existing?.let { put("version", it.version) }
        put("customer", buildJsonObject { put("name", customer.trim()) })
        put("issue_date", issueDate.trim())
        put("due_date", dueDate.trim())
        put("lines", kotlinx.serialization.json.buildJsonArray {
            lines.filter { it.desc.isNotBlank() || it.price.isNotBlank() }.forEach { l ->
                add(buildJsonObject {
                    put("desc", l.desc.trim())
                    put("qty", l.qty.replace(',', '.').trim())
                    put("unitPrice", l.price.replace(',', '.').trim())
                    put("vatRate", l.vat.replace(',', '.').trim())
                })
            }
        })
        put("net", roundStr(net))
        put("vat", roundStr(vat))
        put("gross", roundStr(gross))
        put("vat_rate", lines.firstOrNull()?.vat?.replace(',', '.')?.trim() ?: defaultVat)
        put("note", note.trim())
        put("currency", existing?.currency ?: "EUR")
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(if (id == null) R.string.invoice_new else R.string.invoice_edit),
                onBack = onBack,
                actions = {
                    TextButton(enabled = !busy && customer.isNotBlank(), onClick = {
                        busy = true
                        vm.saveInvoice(id, body()) { ok -> busy = false; if (ok) onBack() }
                    }) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(customer, { customer = it }, R.string.invoice_customer)
            Field(issueDate, { issueDate = it }, R.string.invoice_issue_date)
            Field(dueDate, { dueDate = it }, R.string.invoice_due_date)

            SectionLabel(stringResource(R.string.invoice_lines))
            lines.forEachIndexed { i, l ->
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(l.desc, { l.desc = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.line_desc)) })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField(l.qty, { l.qty = it }, R.string.line_qty, Modifier.weight(1f))
                        NumField(l.price, { l.price = it }, R.string.line_price, Modifier.weight(1f))
                        NumField(l.vat, { l.vat = it }, R.string.line_vat, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(FinanceViewModel.money(l.net), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (lines.size > 1) TextButton(onClick = { lines.removeAt(i) }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            TextButton(onClick = { lines.add(LineRow("", "1", "", defaultVat)) }) { Text(stringResource(R.string.line_add)) }

            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TotalRow(stringResource(R.string.invoice_net), FinanceViewModel.money(net))
                TotalRow(stringResource(R.string.invoice_vat_amount), FinanceViewModel.money(vat))
                TotalRow(stringResource(R.string.invoice_gross), FinanceViewModel.money(gross), bold = true)
            }

            Field(note, { note = it }, R.string.invoice_note)
            if (id != null && existing?.number == null) {
                TextButton(onClick = { vm.finalizeInvoice(id) { } }) { Text(stringResource(R.string.invoice_finalize)) }
            }
            if (id != null) {
                TextButton(onClick = { vm.deleteInvoice(id) { ok -> if (ok) onBack() } }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun roundStr(v: Double): String = (Math.round(v * 100.0) / 100.0).toString()

@Composable
private fun ImportedInvoiceScreen(vm: FinanceViewModel, inv: de.ledgerline.app.domain.model.finance.Invoice, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    AppScaffold(topBar = {
        AppTopBar(title = inv.number ?: stringResource(R.string.invoice_imported), onBack = onBack, actions = {
            if (inv.id != 0) TextButton(onClick = { vm.deleteInvoice(inv.id) { ok -> if (ok) onBack() } }) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow(stringResource(R.string.invoice_number_label), inv.number ?: "—")
                InfoRow(stringResource(R.string.invoice_customer), customerName(inv) ?: "—")
                InfoRow(stringResource(R.string.invoice_issue_date), inv.issueDate ?: "—")
                InfoRow(stringResource(R.string.invoice_gross), FinanceViewModel.money(inv.gross, inv.currency))
                inv.vatRate?.let { InfoRow(stringResource(R.string.invoice_vat_rate), it) }
                InfoRow(stringResource(R.string.invoice_status_label), statusLabel(inv.status))
            }
            if (error) Text(stringResource(R.string.invoice_pdf_error), color = MaterialTheme.colorScheme.error)
            de.ledgerline.app.ui.theme.PrimaryGradientButton(
                text = stringResource(R.string.invoice_open_pdf),
                enabled = !busy,
                onClick = {
                    busy = true; error = false
                    scope.launch {
                        val bytes = vm.invoicePdf(inv.id)
                        busy = false
                        if (bytes == null || !DocOpener.open(ctx, bytes, "invoice-${inv.id}.pdf", "application/pdf")) error = true
                    }
                },
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, label: Int, modifier: Modifier) {
    androidx.compose.material3.OutlinedTextField(
        value, onChange, modifier, label = { Text(stringResource(label)) }, singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
    )
}

@Composable
private fun TotalRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
        Text(value, style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
    }
}

// ===========================================================================
//  Transactions
// ===========================================================================
@Composable
fun TransactionsTab(vm: FinanceViewModel, onEdit: (Int?) -> Unit, onImport: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        val tx = data?.transactions?.sortedByDescending { it.date }.orEmpty()
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onImport) { Text(stringResource(R.string.transactions_import)) }
            }
        if (tx.isEmpty()) EmptyState(stringResource(R.string.transactions_empty))
        else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tx, key = { it.id }) { t ->
                Column(Modifier.fillMaxWidth().clickable { onEdit(t.id) }.cardSurface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.counterparty ?: stringResource(R.string.transaction_untitled), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(FinanceViewModel.money(t.amount), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(listOfNotNull(t.date, t.purpose).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        }
        ExtendedFloatingActionButton(
            onClick = { onEdit(null) },
            icon = { Icon(Icons.Outlined.Add, null) },
            text = { Text(stringResource(R.string.transaction_new)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
fun BulkImportScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val accounts = vm.data.collectAsStateWithLifecycle().value?.paymentMethods?.filter { it.deletedAt == null }.orEmpty()
    var lines by remember { mutableStateOf<List<de.ledgerline.app.core.finance.BankLine>>(emptyList()) }
    var accountId by remember { mutableStateOf<Int?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } }.getOrNull()
            lines = if (text != null) de.ledgerline.app.core.finance.BankCsv.parse(text) else emptyList()
            result = null
        }
    }

    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.transactions_import), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            TextButton(onClick = { picker.launch("*/*") }) { Text(stringResource(R.string.import_pick_csv)) }

            if (lines.isNotEmpty()) {
                Text(stringResource(R.string.import_target_account))
                accounts.forEach { a ->
                    Row(Modifier.fillMaxWidth().clickable { accountId = a.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = accountId == a.id, onClick = { accountId = a.id })
                        Text(a.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                de.ledgerline.app.ui.theme.PrimaryGradientButton(
                    text = stringResource(R.string.import_count, lines.size),
                    enabled = !busy && accountId != null,
                    onClick = {
                        busy = true
                        scope.launch {
                            val r = vm.bulkImport(accountId!!, lines.map { de.ledgerline.app.core.finance.BankCsv.toJson(it) })
                            busy = false
                            result = if (r != null) ctx.getString(R.string.import_result, r.first, r.second) else ctx.getString(R.string.security_failed)
                            if (r != null) lines = emptyList()
                        }
                    },
                )
            } else {
                Text(stringResource(R.string.import_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun TransactionEditScreen(vm: FinanceViewModel, id: Int?, onBack: () -> Unit) {
    val existing = remember(id) { id?.let { vm.transaction(it) } }
    val accounts = vm.data.collectAsStateWithLifecycle().value?.paymentMethods.orEmpty()
    var date by remember { mutableStateOf(existing?.date ?: "") }
    var amount by remember { mutableStateOf(existing?.amount ?: "") }
    var counterparty by remember { mutableStateOf(existing?.counterparty ?: "") }
    var purpose by remember { mutableStateOf(existing?.purpose ?: "") }
    var vatCat by remember { mutableStateOf(existing?.vatCat ?: "") }
    var busy by remember { mutableStateOf(false) }
    val accountId = existing?.paymentMethodId ?: accounts.firstOrNull()?.id

    fun body(): JsonObject = buildJsonObject {
        existing?.let { put("version", it.version) }
        accountId?.let { put("payment_method_id", it) }
        put("date", date.trim())
        put("amount", amount.replace(',', '.').trim())
        put("counterparty", counterparty.trim())
        put("purpose", purpose.trim())
        put("vat_cat", vatCat.trim())
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(if (id == null) R.string.transaction_new else R.string.transaction_edit),
                onBack = onBack,
                actions = {
                    TextButton(enabled = !busy && amount.isNotBlank() && accountId != null, onClick = {
                        busy = true
                        vm.saveTransaction(id, body()) { ok -> busy = false; if (ok) onBack() }
                    }) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (accountId == null) Text(stringResource(R.string.transaction_need_account), color = MaterialTheme.colorScheme.error)
            Field(date, { date = it }, R.string.transaction_date)
            Field(amount, { amount = it }, R.string.transaction_amount)
            Field(counterparty, { counterparty = it }, R.string.transaction_counterparty)
            Field(purpose, { purpose = it }, R.string.transaction_purpose)
            Field(vatCat, { vatCat = it }, R.string.transaction_vat_cat)

            if (id != null) ReceiptsSection(vm, id)

            if (id != null) TextButton(onClick = { vm.deleteTransaction(id) { ok -> if (ok) onBack() } }) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ReceiptsSection(vm: FinanceViewModel, txId: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tx = vm.data.collectAsStateWithLifecycle().value?.transactions?.firstOrNull { it.id == txId }
    val receipts = tx?.receipts.orEmpty()
    var busy by remember { mutableStateOf(false) }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            val name = queryName(ctx, uri) ?: "receipt"
            if (bytes != null) vm.attachReceipt(txId, bytes, name, mime) { busy = false } else busy = false
        }
    }

    SectionLabel(stringResource(R.string.receipts))
    receipts.forEach { r ->
        val rid = jstr(r, "id"); val rname = jstr(r, "name").ifBlank { rid }
        Row(Modifier.fillMaxWidth().cardSurface(), verticalAlignment = Alignment.CenterVertically) {
            Text(rname, Modifier.weight(1f).clickable {
                scope.launch {
                    val bytes = vm.receiptBytes(txId, rid)
                    if (bytes != null) DocOpener.open(ctx, bytes, rname, guessMime(rname))
                }
            }, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { vm.deleteReceipt(txId, rid) { } }) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    TextButton(enabled = !busy, onClick = { picker.launch("*/*") }) { Text(stringResource(R.string.receipt_attach)) }
}

private fun guessMime(name: String): String = when {
    name.endsWith(".pdf", true) -> "application/pdf"
    name.endsWith(".png", true) -> "image/png"
    name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
    else -> "application/octet-stream"
}

private fun queryName(ctx: android.content.Context, uri: android.net.Uri): String? = runCatching {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    }
}.getOrNull()

// ===========================================================================
//  Partners / Payment methods / Projects — simple name-first lists
// ===========================================================================
@Composable
fun PartnersScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Int?>(null) }
    var creating by remember { mutableStateOf(false) }
    val partners = data?.partners?.filter { it.deletedAt == null }?.sortedBy { it.name.lowercase() }.orEmpty()

    if (creating || editing != null) {
        val p = editing?.let { vm.partner(it) }
        var name by remember { mutableStateOf(p?.name ?: "") }
        var email by remember { mutableStateOf(p?.email ?: "") }
        var phone by remember { mutableStateOf(p?.phone ?: "") }
        var category by remember { mutableStateOf(p?.category ?: "") }
        var busy by remember { mutableStateOf(false) }
        fun body() = buildJsonObject {
            p?.let { put("version", it.version) }
            put("name", name.trim()); put("email", email.trim()); put("phone", phone.trim()); put("category", category.trim())
        }
        AppScaffold(topBar = {
            AppTopBar(title = stringResource(R.string.more_partners), onBack = { editing = null; creating = false }, actions = {
                TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                    busy = true; vm.savePartner(editing, body()) { editing = null; creating = false; busy = false }
                }) { Text(stringResource(R.string.action_save)) }
            })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(name, { name = it }, R.string.partner_name)
                Field(email, { email = it }, R.string.partner_email)
                Field(phone, { phone = it }, R.string.partner_phone)
                Field(category, { category = it }, R.string.partner_category)
            }
        }
        return
    }

    NamedListScaffold(
        title = stringResource(R.string.more_partners), onBack = onBack, onAdd = { creating = true },
        items = partners.map { it.id to it.name }, subtitle = { id -> vm.partner(id)?.email }, onClick = { editing = it },
        empty = stringResource(R.string.partners_empty),
    )
}

@Composable
fun PaymentMethodsScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Int?>(null) }
    var creating by remember { mutableStateOf(false) }
    val list = data?.paymentMethods?.filter { it.deletedAt == null }?.sortedBy { it.name.lowercase() }.orEmpty()

    if (creating || editing != null) {
        val p = editing?.let { vm.paymentMethod(it) }
        var name by remember { mutableStateOf(p?.name ?: "") }
        var iban by remember { mutableStateOf(p?.iban ?: "") }
        var holder by remember { mutableStateOf(p?.holder ?: "") }
        var busy by remember { mutableStateOf(false) }
        fun body() = buildJsonObject {
            p?.let { put("version", it.version) }
            put("type", p?.type ?: "bank"); put("name", name.trim()); put("iban", iban.trim()); put("holder", holder.trim())
        }
        AppScaffold(topBar = {
            AppTopBar(title = stringResource(R.string.more_payment_methods), onBack = { editing = null; creating = false }, actions = {
                TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                    busy = true; vm.savePaymentMethod(editing, body()) { editing = null; creating = false; busy = false }
                }) { Text(stringResource(R.string.action_save)) }
            })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(name, { name = it }, R.string.pm_name)
                Field(holder, { holder = it }, R.string.pm_holder)
                Field(iban, { iban = it }, R.string.pm_iban)
            }
        }
        return
    }

    NamedListScaffold(
        title = stringResource(R.string.more_payment_methods), onBack = onBack, onAdd = { creating = true },
        items = list.map { it.id to it.name }, subtitle = { id -> vm.paymentMethod(id)?.iban }, onClick = { editing = it },
        empty = stringResource(R.string.payment_methods_empty),
    )
}

@Composable
fun ProjectsScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Int?>(null) }
    var creating by remember { mutableStateOf(false) }
    val list = data?.projects?.filter { it.deletedAt == null }?.sortedBy { it.name.lowercase() }.orEmpty()

    if (creating || editing != null) {
        val p = editing?.let { vm.project(it) }
        var name by remember { mutableStateOf(p?.name ?: "") }
        var busy by remember { mutableStateOf(false) }
        fun body() = buildJsonObject { p?.let { put("version", it.version) }; put("name", name.trim()); put("kind", p?.kind ?: "business") }
        AppScaffold(topBar = {
            AppTopBar(title = stringResource(R.string.more_projects), onBack = { editing = null; creating = false }, actions = {
                TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                    busy = true; vm.saveProject(editing, body()) { editing = null; creating = false; busy = false }
                }) { Text(stringResource(R.string.action_save)) }
            })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(name, { name = it }, R.string.project_name)
            }
        }
        return
    }

    NamedListScaffold(
        title = stringResource(R.string.more_projects), onBack = onBack, onAdd = { creating = true },
        items = list.map { it.id to it.name }, subtitle = { null }, onClick = { editing = it },
        empty = stringResource(R.string.projects_empty),
    )
}

// ===========================================================================
//  Company profile
// ===========================================================================
@Composable
fun CompanyScreen(vm: FinanceViewModel, onBack: () -> Unit) {
    var profile by remember { mutableStateOf<CompanyProfile?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { profile = vm.loadCompany() ?: CompanyProfile() }
    val p = profile
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
        return
    }
    var name by remember(p) { mutableStateOf(p.companyName ?: "") }
    var address by remember(p) { mutableStateOf(p.companyAddress ?: "") }
    var email by remember(p) { mutableStateOf(p.companyEmail ?: "") }
    var vatId by remember(p) { mutableStateOf(p.companyVatId ?: "") }
    var iban by remember(p) { mutableStateOf(p.companyIban ?: "") }
    var busy by remember { mutableStateOf(false) }
    AppScaffold(topBar = {
        AppTopBar(title = stringResource(R.string.more_company), onBack = onBack, actions = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                vm.saveCompany(p.copy(companyName = name, companyAddress = address, companyEmail = email, companyVatId = vatId, companyIban = iban)) { ok -> busy = false; if (ok) onBack() }
            }) { Text(stringResource(R.string.action_save)) }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(name, { name = it }, R.string.company_name)
            Field(address, { address = it }, R.string.company_address)
            Field(email, { email = it }, R.string.company_email)
            Field(vatId, { vatId = it }, R.string.company_vat_id)
            Field(iban, { iban = it }, R.string.company_iban)
        }
    }
}

// ===========================================================================
//  Shared bits
// ===========================================================================
@Composable
internal fun Field(value: String, onChange: (String) -> Unit, label: Int) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, singleLine = true)
}

@Composable
internal fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NamedListScaffold(
    title: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    items: List<Pair<Int, String>>,
    subtitle: (Int) -> String?,
    onClick: (Int) -> Unit,
    empty: String,
) {
    AppScaffold(topBar = { AppTopBar(title = title, onBack = onBack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (items.isEmpty()) EmptyState(empty)
            else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.first }) { (id, name) ->
                    Column(Modifier.fillMaxWidth().clickable { onClick(id) }.cardSurface(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        subtitle(id)?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text(stringResource(R.string.action_add)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}
