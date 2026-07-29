package de.ledgerline.app.ui.finance

import de.ledgerline.app.ui.common.SectionLabel
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    vm.transactions.collectAsStateWithLifecycle()
    val current = vm.transactions.value.firstOrNull { it.id == initial.id } ?: initial
    val exists = vm.transactions.value.any { it.id == initial.id }
    var viewing by remember { mutableStateOf<de.ledgerline.app.domain.model.Receipt?>(null) }
    val v = viewing
    if (v != null) { ReceiptViewerScreen(vm.transactions.value.firstOrNull { it.id == initial.id } ?: initial, v, vm, onBack = { viewing = null }); return }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val attach = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "receipt"
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        if (bytes == null) { android.widget.Toast.makeText(ctx, ctx.getString(R.string.finance_import_unreadable), android.widget.Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult }
        vm.attachReceipt(current, bytes, name, mime) { res ->
            val msg = when (res) { true -> R.string.finance_receipt_added; null -> R.string.finance_receipt_dupe; else -> R.string.finance_import_failed }
            android.widget.Toast.makeText(ctx, ctx.getString(msg), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val receipts = vm.receiptsOf(current)

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
            SectionLabel(stringResource(R.string.finance_tx_vatcat))
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

            // Receipts (attach documents to this booking) — only for a saved booking.
            if (exists) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.finance_receipts), Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { attach.launch(arrayOf("image/*", "application/pdf")) }) { Text(stringResource(R.string.finance_receipt_attach)) }
                }
                if (receipts.isNotEmpty()) Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
                    receipts.forEachIndexed { i, r ->
                        if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(Modifier.fillMaxWidth().clickable { viewing = r }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name.ifBlank { stringResource(R.string.finance_receipt) }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                r.total?.let { Text(vm.money2(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            IconButton(onClick = { vm.deleteReceipt(current, r) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * In-app receipt viewer: decrypts the document in memory and renders it (images via BitmapFactory,
 * PDFs via AOSP PdfRenderer). PDF rendering needs a seekable fd, so the plaintext is written to an
 * app-private cache file JUST for the render and deleted immediately after — no lingering plaintext.
 * Also edits the receipt's recognised total + project assignment.
 */
@Composable
fun ReceiptViewerScreen(tx: de.ledgerline.app.domain.model.Transaction, r: de.ledgerline.app.domain.model.Receipt, vm: FinanceViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var pages by remember(r.id) { mutableStateOf<List<androidx.compose.ui.graphics.ImageBitmap>?>(null) }
    var total by remember(r.id) { mutableStateOf(r.total?.let { trimAmount(it) } ?: "") }
    androidx.compose.runtime.LaunchedEffect(r.id) {
        val bytes = vm.loadReceiptBytes(r)
        pages = if (bytes == null) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { decodeReceiptPages(ctx, bytes, r.mime, r.name) }
    }
    AppScaffold(
        topBar = {
            AppTopBar(title = r.name.ifBlank { stringResource(R.string.finance_receipt) }, onBack = onBack, actions = {
                androidx.compose.material3.IconButton(onClick = {
                    vm.updateReceipt(tx, r.copy(total = total.replace(',', '.').trim().toDoubleOrNull())) { if (it) onBack() }
                }) { Icon(Icons.Outlined.Check, stringResource(R.string.action_save)) }
            })
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(total, { total = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_receipt_total)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            when {
                pages == null -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
                pages!!.isEmpty() -> Text(stringResource(R.string.finance_receipt_no_preview), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> pages!!.forEach { bmp ->
                    androidx.compose.foundation.Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
                }
            }
        }
    }
}

/** Render receipt bytes to page bitmaps: images directly, PDFs via a short-lived private temp file. */
private fun decodeReceiptPages(ctx: android.content.Context, bytes: ByteArray, mime: String, name: String): List<androidx.compose.ui.graphics.ImageBitmap> {
    val isPdf = mime.contains("pdf", true) || name.endsWith(".pdf", true)
    if (!isPdf) {
        val bmp = runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        return if (bmp != null) listOf(bmp.asImageBitmap()) else emptyList()
    }
    val tmp = runCatching { java.io.File.createTempFile("rcpt", ".pdf", ctx.cacheDir) }.getOrNull() ?: return emptyList()
    return try {
        tmp.writeBytes(bytes)
        android.os.ParcelFileDescriptor.open(tmp, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                (0 until renderer.pageCount.coerceAtMost(30)).map { i ->
                    renderer.openPage(i).use { page ->
                        val w = 1240
                        val h = (w.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp.asImageBitmap()
                    }
                }
            }
        }
    } catch (_: Throwable) {
        emptyList()
    } finally {
        tmp.delete()   // never leave decrypted plaintext on disk
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
