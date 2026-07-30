package de.ledgerline.app.ui.finance

import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.graphics.asImageBitmap
import de.ledgerline.app.ui.common.SectionLabel
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
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val all = vm.sortedPartners()
    val list = if (q.isEmpty()) all else all.filter {
        it.name.lowercase().contains(q) || it.category.lowercase().contains(q) ||
            it.contacts.any { c -> c.name.lowercase().contains(q) } || it.email.lowercase().contains(q)
    }
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.finance_partners), onBack = onBack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text(stringResource(R.string.finance_partner_search)) },
                    singleLine = true, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                )
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
        val logo = remember(p.logo) { decodeDataUri(p.logo) }
        if (logo != null) {
            androidx.compose.foundation.Image(
                bitmap = logo, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(34.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp)),
            )
        } else {
            de.ledgerline.app.ui.theme.IconChip(androidx.compose.material.icons.Icons.Outlined.Groups, tint = de.ledgerline.app.ui.theme.Brand.tintBlue, size = 34.dp)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            val sub = listOfNotNull(p.category.takeIf { it.isNotBlank() }, p.contacts.firstOrNull()?.name?.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun PartnerEdit(initial: Partner, vm: FinanceViewModel, onBack: () -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var category by remember(initial) { mutableStateOf(initial.category) }
    var url by remember(initial) { mutableStateOf(initial.url) }
    var address by remember(initial) { mutableStateOf(initial.address) }
    var email by remember(initial) { mutableStateOf(initial.email) }
    var phone by remember(initial) { mutableStateOf(initial.phone) }
    var vatId by remember(initial) { mutableStateOf(initial.vatId) }
    var note by remember(initial) { mutableStateOf(initial.note) }
    val contacts = remember(initial) { initial.contacts.toMutableStateList() }
    val exists = vm.partnerById(initial.id) != null

    fun persist() {
        vm.savePartner(
            initial.copy(
                name = name.trim(), category = category.trim(), url = url.trim(), address = address.trim(),
                email = email.trim(), phone = phone.trim(), vatId = vatId.trim(), note = note.trim(),
                contacts = contacts.filter { it.name.isNotBlank() },
            ),
        ) { if (it) onBack() }
    }

    AppScaffold(topBar = {
        AppTopBar(
            title = stringResource(if (exists) R.string.finance_partner_edit else R.string.finance_partner_new),
            onBack = onBack,
            actions = {
                if (exists) IconButton(onClick = { vm.deletePartner(initial) { if (it) onBack() } }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                androidx.compose.material3.TextButton(onClick = { persist() }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) }
            },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.finance_partner_details))
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_partner_name)) }, singleLine = true)
                OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_partner_category)) }, singleLine = true)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_partner_url)) }, singleLine = true)
                OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_customer_address)) })
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_customer_email)) }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_company_phone)) }, singleLine = true)
                OutlinedTextField(vatId, { vatId = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_customer_vatid)) }, singleLine = true)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_pm_note)) })
            }

            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.finance_partner_contacts), Modifier.weight(1f))
                    IconButton(onClick = { contacts.add(de.ledgerline.app.domain.model.PartnerContact()) }) { Icon(Icons.Outlined.Add, stringResource(R.string.finance_partner_contact_add)) }
                }
                if (contacts.isEmpty()) Text(stringResource(R.string.finance_partner_no_contacts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                contacts.forEachIndexed { i, ct ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(ct.name, { contacts[i] = ct.copy(name = it) }, Modifier.weight(1f), label = { Text(stringResource(R.string.finance_partner_contact_name)) }, singleLine = true)
                            IconButton(onClick = { contacts.removeAt(i) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                        }
                        OutlinedTextField(ct.role, { contacts[i] = ct.copy(role = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_partner_contact_role)) }, singleLine = true)
                        OutlinedTextField(ct.email, { contacts[i] = ct.copy(email = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_customer_email)) }, singleLine = true)
                        OutlinedTextField(ct.phone, { contacts[i] = ct.copy(phone = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_company_phone)) }, singleLine = true)
                    }
                }
            }
        }
    }
}

/** Decode a `data:image/…;base64,…` URI to an ImageBitmap, or null. */
private fun decodeDataUri(uri: String): androidx.compose.ui.graphics.ImageBitmap? {
    if (!uri.startsWith("data:")) return null
    val b64 = uri.substringAfter("base64,", "").takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
