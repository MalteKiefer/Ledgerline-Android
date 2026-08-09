package de.ledgerline.app.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.DocOpener
import de.ledgerline.app.ui.common.LedgerRow
import de.ledgerline.app.ui.common.ListBottomPadding
import de.ledgerline.app.ui.common.SoftIconChip
import de.ledgerline.app.ui.common.listSection
import de.ledgerline.app.ui.theme.Brand
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Standalone receipts (Fremdbelege): list, add via SAF, view externally, delete. */
@Composable
fun ReceiptsScreen(vm: FinanceViewModel, onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by vm.data.collectAsStateWithLifecycle()
    val receipts = data?.standaloneReceipts.orEmpty().filter { it.deletedAt == null }
    var editing by remember { mutableStateOf<de.ledgerline.app.domain.model.finance.FinanceReceipt?>(null) }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) scope.launch {
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@launch
            val name = queryName(ctx, uri) ?: "receipt"
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            vm.storeReceipt(bytes, name, mime) {}
        }
    }

    AppScaffold(topBar = {
        AppTopBar(title = stringResource(R.string.receipts_title), onBack = onBack, actions = {
            TextButton(onClick = { picker.launch("*/*") }) { Text(stringResource(R.string.receipts_add)) }
        })
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (receipts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.receipts_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                listSection(receipts, key = { "r${it.id}" }) { r ->
                    LedgerRow(
                        title = r.name,
                        subtitle = listOfNotNull(r.category, r.createdAt?.take(10)).joinToString(" · ").ifBlank { null },
                        leading = { SoftIconChip(Icons.AutoMirrored.Outlined.ReceiptLong, tint = Brand.tintOrange) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { editing = r }) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit)) }
                                IconButton(onClick = { vm.deleteStandaloneReceipt(r.id) {} }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                                }
                            }
                        },
                        onClick = {
                            scope.launch {
                                val bytes = vm.standaloneReceiptBytes(r.id) ?: return@launch
                                DocOpener.open(ctx, bytes, r.name, r.mime ?: "application/octet-stream")
                            }
                        },
                    )
                }
            }
        }
    }

    editing?.let { r ->
        ReceiptEditDialog(r, onConfirm = { cat, vat, note ->
            editing = null
            vm.updateReceipt(r.id, buildJsonObject {
                put("version", r.version)
                put("category", cat)
                put("vat", vat)
                put("note", note)
            }) {}
        }, onDismiss = { editing = null })
    }
}

@Composable
private fun ReceiptEditDialog(
    r: de.ledgerline.app.domain.model.finance.FinanceReceipt,
    onConfirm: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var category by remember { mutableStateOf(r.category ?: "") }
    var vat by remember { mutableStateOf(r.vat ?: "") }
    var note by remember { mutableStateOf(r.note ?: "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.receipt_edit)) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(category, { category = it }, label = { Text(stringResource(R.string.receipt_category)) }, singleLine = true)
                androidx.compose.material3.OutlinedTextField(vat, { vat = it }, label = { Text(stringResource(R.string.receipt_vat)) }, singleLine = true)
                androidx.compose.material3.OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.receipt_note)) }, minLines = 2)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(category.trim(), vat.trim(), note.trim()) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun queryName(ctx: android.content.Context, uri: android.net.Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
