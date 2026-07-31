package de.ledgerline.app.ui.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
                        val creditTag = if (inv.isCreditNote) stringResource(R.string.invoice_credit_note) else null
                        Text(
                            listOfNotNull(customerName(inv), inv.issueDate, statusLabel(inv.status), creditTag, importedTag).joinToString(" · "),
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
    var custAttn by remember { mutableStateOf(existing?.let { jstr(it.customer, "attn") } ?: "") }
    var custAddress by remember { mutableStateOf(existing?.let { jstr(it.customer, "address") } ?: "") }
    var custEmail by remember { mutableStateOf(existing?.let { jstr(it.customer, "email") } ?: "") }
    var custVatId by remember { mutableStateOf(existing?.let { jstr(it.customer, "vatId") } ?: "") }
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
    var invoiceEmail by remember { mutableStateOf(existing?.invoiceEmail ?: "") }
    var discountValue by remember { mutableStateOf(existing?.discountValue ?: "") }
    var discountPercent by remember { mutableStateOf((existing?.discountType ?: "percent") == "percent") }
    var skontoPercent by remember { mutableStateOf(existing?.skontoPercent ?: "") }
    var skontoDays by remember { mutableStateOf(existing?.skontoDays?.toString() ?: "") }
    var busy by remember { mutableStateOf(false) }

    val lineNet = lines.sumOf { it.net }
    val discAmount = run {
        val v = discountValue.replace(',', '.').toDoubleOrNull() ?: 0.0
        if (v <= 0) 0.0 else if (discountPercent) lineNet * v / 100.0 else v
    }
    val netBase = (lineNet - discAmount).coerceAtLeast(0.0)
    val factor = if (lineNet > 0) netBase / lineNet else 1.0 // scale each line's VAT by the discount
    val vat = lines.sumOf { it.vatAmount } * factor
    val net = netBase
    val gross = net + vat

    fun body(): JsonObject = buildJsonObject {
        existing?.let { put("version", it.version) }
        put("customer", buildJsonObject {
            put("name", customer.trim())
            if (custAttn.isNotBlank()) put("attn", custAttn.trim())
            if (custAddress.isNotBlank()) put("address", custAddress.trim())
            if (custEmail.isNotBlank()) put("email", custEmail.trim())
            if (custVatId.isNotBlank()) put("vatId", custVatId.trim())
            if (invoiceEmail.isNotBlank()) put("invoiceEmail", invoiceEmail.trim())
        })
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
        if (discountValue.isNotBlank()) {
            put("discount_type", if (discountPercent) "percent" else "amount")
            put("discount_value", discountValue.replace(',', '.').trim())
        }
        if (skontoPercent.isNotBlank()) put("skonto_percent", skontoPercent.replace(',', '.').trim())
        skontoDays.toIntOrNull()?.let { put("skonto_days", it) }
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
            Field(custAttn, { custAttn = it }, R.string.invoice_customer_attn)
            Field(custAddress, { custAddress = it }, R.string.invoice_customer_address)
            Field(custEmail, { custEmail = it }, R.string.invoice_customer_email)
            Field(custVatId, { custVatId = it }, R.string.invoice_customer_vat_id)
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

            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.invoice_discount))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumField(discountValue, { discountValue = it }, R.string.invoice_discount_value, Modifier.weight(1f))
                    androidx.compose.material3.FilterChip(selected = discountPercent, onClick = { discountPercent = true }, label = { Text("%") })
                    androidx.compose.material3.FilterChip(selected = !discountPercent, onClick = { discountPercent = false }, label = { Text("€") })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(skontoPercent, { skontoPercent = it }, R.string.invoice_skonto_percent, Modifier.weight(1f))
                    NumField(skontoDays, { skontoDays = it }, R.string.invoice_skonto_days, Modifier.weight(1f))
                }
            }

            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TotalRow(stringResource(R.string.invoice_net), FinanceViewModel.money(net))
                TotalRow(stringResource(R.string.invoice_vat_amount), FinanceViewModel.money(vat))
                TotalRow(stringResource(R.string.invoice_gross), FinanceViewModel.money(gross), bold = true)
            }

            Field(invoiceEmail, { invoiceEmail = it }, R.string.invoice_email_field)
            Field(note, { note = it }, R.string.invoice_note)

            if (id != null) InvoicePdfSection(vm, id, hasPdf = existing?.pdfPath != null)

            // Lifecycle actions for a finalized (numbered) non-credit-note invoice (web parity).
            val finalized = existing?.number != null
            if (id != null && finalized && existing?.isCreditNote != true) {
                var msg by remember { mutableStateOf<String?>(null) }
                msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val emailCtx = stringResource(R.string.invoice_email_sent)
                    val dunCtx = stringResource(R.string.invoice_dun_sent)
                    TextButton(onClick = { vm.emailInvoice(id, invoiceEmail.ifBlank { null }) { ok -> if (ok) msg = emailCtx } }) { Text(stringResource(R.string.invoice_email_send)) }
                    TextButton(onClick = { vm.dunInvoice(id, invoiceEmail.ifBlank { null }) { ok -> if (ok) msg = dunCtx } }) { Text(stringResource(R.string.invoice_dun)) }
                    TextButton(onClick = { vm.stornoInvoice(id) { ok -> if (ok) onBack() } }) { Text(stringResource(R.string.invoice_storno), color = MaterialTheme.colorScheme.error) }
                }
                if (existing != null && existing.reminderCount > 0) {
                    Text(stringResource(R.string.invoice_reminder_level) + " " + existing.reminderCount, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

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
private fun InvoicePdfSection(vm: FinanceViewModel, id: Int, hasPdf: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null) vm.uploadInvoicePdf(id, bytes, queryName(ctx, uri) ?: "invoice.pdf") { busy = false } else busy = false
        }
    }
    SectionLabel(stringResource(R.string.invoice_pdf))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasPdf) TextButton(onClick = {
            scope.launch { vm.invoicePdf(id)?.let { DocOpener.open(ctx, it, "invoice-$id.pdf", "application/pdf") } }
        }) { Text(stringResource(R.string.invoice_open_pdf)) }
        TextButton(enabled = !busy, onClick = { picker.launch("application/pdf") }) { Text(stringResource(R.string.invoice_upload_pdf)) }
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
    val projects = vm.data.collectAsStateWithLifecycle().value?.projects?.filter { it.deletedAt == null }.orEmpty()
    var date by remember { mutableStateOf(existing?.date ?: "") }
    var amount by remember { mutableStateOf(existing?.amount ?: "") }
    var counterparty by remember { mutableStateOf(existing?.counterparty ?: "") }
    var counterpartyIban by remember { mutableStateOf(existing?.counterpartyIban ?: "") }
    var bic by remember { mutableStateOf(existing?.bic ?: "") }
    var purpose by remember { mutableStateOf(existing?.purpose ?: "") }
    var bookingText by remember { mutableStateOf(existing?.bookingText ?: "") }
    var vatCat by remember { mutableStateOf(existing?.vatCat ?: "") }
    var projectId by remember { mutableStateOf(existing?.financeProjectId) }
    var busy by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf(existing?.paymentMethodId ?: accounts.firstOrNull()?.id) }

    fun body(): JsonObject = buildJsonObject {
        existing?.let { put("version", it.version) }
        accountId?.let { put("payment_method_id", it) }
        put("date", date.trim())
        put("amount", amount.replace(',', '.').trim())
        put("counterparty", counterparty.trim())
        put("counterparty_iban", counterpartyIban.trim())
        put("bic", bic.trim())
        put("purpose", purpose.trim())
        put("booking_text", bookingText.trim())
        put("vat_cat", vatCat.trim())
        projectId?.let { put("finance_project_id", it) }
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
            if (accounts.isEmpty()) Text(stringResource(R.string.transaction_need_account), color = MaterialTheme.colorScheme.error)
            else {
                SectionLabel(stringResource(R.string.transaction_account))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    accounts.filter { it.deletedAt == null }.forEach { a ->
                        androidx.compose.material3.FilterChip(selected = accountId == a.id, onClick = { accountId = a.id }, label = { Text(a.name) })
                    }
                }
            }
            Field(date, { date = it }, R.string.transaction_date)
            Field(amount, { amount = it }, R.string.transaction_amount)
            Field(counterparty, { counterparty = it }, R.string.transaction_counterparty)
            Field(counterpartyIban, { counterpartyIban = it }, R.string.transaction_counterparty_iban)
            Field(bic, { bic = it }, R.string.transaction_bic)
            Field(purpose, { purpose = it }, R.string.transaction_purpose)
            Field(bookingText, { bookingText = it }, R.string.transaction_booking_text)
            Field(vatCat, { vatCat = it }, R.string.transaction_vat_cat)
            if (projects.isNotEmpty()) {
                SectionLabel(stringResource(R.string.transaction_project))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = projectId == null, onClick = { projectId = null }, label = { Text(stringResource(R.string.transaction_project_none)) })
                    projects.forEach { pr ->
                        androidx.compose.material3.FilterChip(selected = projectId == pr.id, onClick = { projectId = pr.id }, label = { Text(pr.name) })
                    }
                }
            }

            if (id != null) ReceiptsSection(vm, id, onOcrText = { if (purpose.isBlank()) purpose = it })

            if (id != null) TextButton(onClick = { vm.deleteTransaction(id) { ok -> if (ok) onBack() } }) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ReceiptsSection(vm: FinanceViewModel, txId: Int, onOcrText: (String) -> Unit) {
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

    // OCR: scan a receipt image/PDF → extract text → offer it as the purpose (transient; nothing stored server-side).
    val ocrPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            val text = if (bytes != null) vm.ocr(bytes, queryName(ctx, uri) ?: "scan", mime) else null
            busy = false
            text?.takeIf { it.isNotBlank() }?.let { onOcrText(it.lines().firstOrNull { l -> l.isNotBlank() }?.take(140) ?: it.take(140)) }
        }
    }

    SectionLabel(stringResource(R.string.receipts))
    receipts.forEach { r ->
        val rid = jstr(r, "id"); val rname = jstr(r, "name").ifBlank { rid }
        val locked = (r["locked"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        Row(Modifier.fillMaxWidth().cardSurface(), verticalAlignment = Alignment.CenterVertically) {
            Text(rname, Modifier.weight(1f).clickable {
                scope.launch {
                    val bytes = vm.receiptBytes(txId, rid)
                    if (bytes != null) DocOpener.open(ctx, bytes, rname, guessMime(rname))
                }
            }, style = MaterialTheme.typography.bodyMedium)
            // A locked receipt (e.g. an auto-linked invoice) cannot be deleted (server rejects it).
            if (!locked) TextButton(onClick = { vm.deleteReceipt(txId, rid) { } }) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = !busy, onClick = { picker.launch("*/*") }) { Text(stringResource(R.string.receipt_attach)) }
        TextButton(enabled = !busy, onClick = { ocrPicker.launch("image/*") }) { Text(stringResource(R.string.receipt_ocr)) }
    }
}

@Composable
private fun pmTypeLabel(t: String): String = stringResource(
    when (t) {
        "bank" -> R.string.pm_type_bank
        "card" -> R.string.pm_type_card
        "paypal" -> R.string.pm_type_paypal
        "cash" -> R.string.pm_type_cash
        else -> R.string.pm_type_other
    },
)

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
        var invoiceEmail by remember { mutableStateOf(p?.invoiceEmail ?: "") }
        var phone by remember { mutableStateOf(p?.phone ?: "") }
        var category by remember { mutableStateOf(p?.category ?: "") }
        var kind by remember { mutableStateOf(p?.kind ?: "") }
        var address by remember { mutableStateOf(p?.address ?: "") }
        var vatId by remember { mutableStateOf(p?.vatId ?: "") }
        var url by remember { mutableStateOf(p?.url ?: "") }
        var note by remember { mutableStateOf(p?.note ?: "") }
        var busy by remember { mutableStateOf(false) }
        fun body() = buildJsonObject {
            p?.let { put("version", it.version) }
            put("name", name.trim()); put("email", email.trim()); put("phone", phone.trim()); put("category", category.trim())
            put("kind", kind.trim()); put("address", address.trim()); put("vat_id", vatId.trim())
            put("url", url.trim()); put("note", note.trim()); put("invoice_email", invoiceEmail.trim())
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
                Field(category, { category = it }, R.string.partner_category)
                Field(address, { address = it }, R.string.partner_address)
                Field(email, { email = it }, R.string.partner_email)
                Field(invoiceEmail, { invoiceEmail = it }, R.string.partner_invoice_email)
                Field(phone, { phone = it }, R.string.partner_phone)
                Field(vatId, { vatId = it }, R.string.partner_vat_id)
                Field(url, { url = it }, R.string.partner_url)
                Field(note, { note = it }, R.string.partner_note)
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
        var type by remember { mutableStateOf(p?.type ?: "bank") }
        var name by remember { mutableStateOf(p?.name ?: "") }
        var holder by remember { mutableStateOf(p?.holder ?: "") }
        var iban by remember { mutableStateOf(p?.iban ?: "") }
        var bic by remember { mutableStateOf(p?.bic ?: "") }
        var bank by remember { mutableStateOf(p?.bank ?: "") }
        var accountNo by remember { mutableStateOf(p?.accountNo ?: "") }
        var cardNumber by remember { mutableStateOf(p?.cardNumber ?: "") }
        var cardNetwork by remember { mutableStateOf(p?.cardNetwork ?: "") }
        var cardExpiry by remember { mutableStateOf(p?.cardExpiry ?: "") }
        var paypalEmail by remember { mutableStateOf(p?.paypalEmail ?: "") }
        var note by remember { mutableStateOf(p?.note ?: "") }
        var business by remember { mutableStateOf(p?.business ?: false) }
        var busy by remember { mutableStateOf(false) }
        fun body() = buildJsonObject {
            p?.let { put("version", it.version) }
            put("type", type); put("name", name.trim()); put("holder", holder.trim())
            put("note", note.trim()); put("business", business)
            when (type) {
                "bank" -> { put("iban", iban.trim()); put("bic", bic.trim()); put("bank", bank.trim()); put("account_no", accountNo.trim()) }
                "card" -> { put("card_number", cardNumber.trim()); put("card_network", cardNetwork.trim()); put("card_expiry", cardExpiry.trim()) }
                "paypal" -> put("paypal_email", paypalEmail.trim())
            }
        }
        AppScaffold(topBar = {
            AppTopBar(title = stringResource(R.string.more_payment_methods), onBack = { editing = null; creating = false }, actions = {
                TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                    busy = true; vm.savePaymentMethod(editing, body()) { editing = null; creating = false; busy = false }
                }) { Text(stringResource(R.string.action_save)) }
            })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel(stringResource(R.string.pm_type))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("bank", "card", "paypal", "cash", "other").forEach { t ->
                        androidx.compose.material3.FilterChip(selected = type == t, onClick = { type = t }, label = { Text(pmTypeLabel(t)) })
                    }
                }
                Field(name, { name = it }, R.string.pm_name)
                Field(holder, { holder = it }, R.string.pm_holder)
                when (type) {
                    "bank" -> {
                        Field(iban, { iban = it }, R.string.pm_iban)
                        Field(bic, { bic = it }, R.string.pm_bic)
                        Field(bank, { bank = it }, R.string.pm_bank)
                        Field(accountNo, { accountNo = it }, R.string.pm_account_no)
                    }
                    "card" -> {
                        Field(cardNumber, { cardNumber = it }, R.string.pm_card_number)
                        Field(cardNetwork, { cardNetwork = it }, R.string.pm_card_network)
                        Field(cardExpiry, { cardExpiry = it }, R.string.pm_card_expiry)
                    }
                    "paypal" -> Field(paypalEmail, { paypalEmail = it }, R.string.pm_paypal_email)
                }
                Field(note, { note = it }, R.string.pm_note)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pm_business), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    androidx.compose.material3.Switch(checked = business, onCheckedChange = { business = it })
                }
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
        var kind by remember { mutableStateOf(p?.kind ?: "business") }
        var note by remember { mutableStateOf(p?.note ?: "") }
        var busy by remember { mutableStateOf(false) }
        fun body() = buildJsonObject {
            p?.let { put("version", it.version) }
            put("name", name.trim()); put("kind", kind); put("note", note.trim())
        }
        AppScaffold(topBar = {
            AppTopBar(title = stringResource(R.string.more_projects), onBack = { editing = null; creating = false }, actions = {
                TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                    busy = true; vm.saveProject(editing, body()) { editing = null; creating = false; busy = false }
                }) { Text(stringResource(R.string.action_save)) }
            })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(name, { name = it }, R.string.project_name)
                SectionLabel(stringResource(R.string.project_kind))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = kind == "business", onClick = { kind = "business" }, label = { Text(stringResource(R.string.project_kind_business)) })
                    androidx.compose.material3.FilterChip(selected = kind == "private", onClick = { kind = "private" }, label = { Text(stringResource(R.string.project_kind_private)) })
                }
                Field(note, { note = it }, R.string.project_note)
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
    var phone by remember(p) { mutableStateOf(p.companyPhone ?: "") }
    var taxId by remember(p) { mutableStateOf(p.companyTaxId ?: "") }
    var vatId by remember(p) { mutableStateOf(p.companyVatId ?: "") }
    var iban by remember(p) { mutableStateOf(p.companyIban ?: "") }
    var bic by remember(p) { mutableStateOf(p.companyBic ?: "") }
    var bankName by remember(p) { mutableStateOf(p.companyBankName ?: "") }
    var numberFormat by remember(p) { mutableStateOf(p.invoiceNumberFormat ?: "") }
    var defaultVat by remember(p) { mutableStateOf(p.invoiceDefaultVatRate?.let { egStr(it) } ?: "") }
    var termsDays by remember(p) { mutableStateOf(p.invoicePaymentTermsDays?.toString() ?: "") }
    var footer by remember(p) { mutableStateOf(p.invoiceFooterText ?: "") }
    var smallBusiness by remember(p) { mutableStateOf(p.smallBusiness ?: false) }
    var busy by remember { mutableStateOf(false) }
    AppScaffold(topBar = {
        AppTopBar(title = stringResource(R.string.more_company), onBack = onBack, actions = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                vm.saveCompany(p.copy(
                    companyName = name, companyAddress = address, companyEmail = email, companyPhone = phone,
                    companyTaxId = taxId, companyVatId = vatId, companyIban = iban, companyBic = bic, companyBankName = bankName,
                    invoiceNumberFormat = numberFormat.ifBlank { null },
                    invoiceDefaultVatRate = defaultVat.replace(',', '.').toDoubleOrNull(),
                    invoicePaymentTermsDays = termsDays.toIntOrNull(),
                    invoiceFooterText = footer.ifBlank { null }, smallBusiness = smallBusiness,
                )) { ok -> busy = false; if (ok) onBack() }
            }) { Text(stringResource(R.string.action_save)) }
        })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(stringResource(R.string.company_identity))
            Field(name, { name = it }, R.string.company_name)
            Field(address, { address = it }, R.string.company_address)
            Field(email, { email = it }, R.string.company_email)
            Field(phone, { phone = it }, R.string.company_phone)
            SectionLabel(stringResource(R.string.company_tax))
            Field(taxId, { taxId = it }, R.string.company_tax_id)
            Field(vatId, { vatId = it }, R.string.company_vat_id)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.company_small_business), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.Switch(checked = smallBusiness, onCheckedChange = { smallBusiness = it })
            }
            SectionLabel(stringResource(R.string.company_bank))
            Field(iban, { iban = it }, R.string.company_iban)
            Field(bic, { bic = it }, R.string.company_bic)
            Field(bankName, { bankName = it }, R.string.company_bank_name)
            SectionLabel(stringResource(R.string.company_invoice_defaults))
            Field(numberFormat, { numberFormat = it }, R.string.company_number_format)
            Field(defaultVat, { defaultVat = it }, R.string.company_default_vat)
            Field(termsDays, { termsDays = it }, R.string.company_terms_days)
            Field(footer, { footer = it }, R.string.company_footer)
        }
    }
}

private fun egStr(v: Double) = if (v == kotlin.math.floor(v)) v.toLong().toString() else v.toString()

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
