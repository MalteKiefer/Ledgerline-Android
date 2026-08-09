package de.ledgerline.app.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.finance.FinanceCategory
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.LedgerRow
import de.ledgerline.app.ui.common.ListBottomPadding
import de.ledgerline.app.ui.common.listSection
import de.ledgerline.app.ui.files.parseHex
import de.ledgerline.app.ui.theme.Brand
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val CATEGORY_COLORS = listOf("#7066F5", "#3B9FD6", "#59AD6B", "#E2915A", "#3FAE9F", "#9E70FA", "#6B7280", "#D6455D")

/** Finance categories: list + create/edit (name + colour) + delete. */
@Composable
fun CategoriesScreen(vm: FinanceViewModel, onBack: (() -> Unit)? = null) {
    val data by vm.data.collectAsStateWithLifecycle()
    val categories = data?.financeCategories.orEmpty()
    var editing by remember { mutableStateOf<FinanceCategory?>(null) }
    var creating by remember { mutableStateOf(false) }

    AppScaffold(topBar = {
        AppTopBar(title = stringResource(R.string.categories_title), onBack = onBack, actions = {
            TextButton(onClick = { creating = true }) { Text(stringResource(R.string.action_add)) }
        })
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (categories.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.categories_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                listSection(categories, key = { "c${it.id}" }) { c ->
                    LedgerRow(
                        title = c.name,
                        leading = { Box(Modifier.size(24.dp).clip(CircleShape).background(parseHex(c.color ?: "#6b7280"))) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { editing = c }) { Text(stringResource(R.string.action_edit)) }
                                IconButton(onClick = { vm.deleteCategory(c.id) {} }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
                            }
                        },
                    )
                }
            }
        }
    }

    if (creating) CategoryEditDialog(
        initial = null,
        onConfirm = { name, color -> creating = false; vm.saveCategory(null, body(name, color, null)) {} },
        onDismiss = { creating = false },
    )
    editing?.let { c ->
        CategoryEditDialog(
            initial = c,
            onConfirm = { name, color -> editing = null; vm.saveCategory(c.id, body(name, color, c)) {} },
            onDismiss = { editing = null },
        )
    }
}

private fun body(name: String, color: String, existing: FinanceCategory?) = buildJsonObject {
    put("name", name); put("color", color)
    existing?.icon?.let { put("icon", it) }
}

@Composable
private fun CategoryEditDialog(initial: FinanceCategory?, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: CATEGORY_COLORS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.action_add else R.string.action_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.category_name)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CATEGORY_COLORS.forEach { hex ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(parseHex(hex)).clickable { color = hex },
                            contentAlignment = Alignment.Center,
                        ) { if (color == hex) Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim(), color) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
