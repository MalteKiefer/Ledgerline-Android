package de.ledgerline.app.ui.workspace.contacts

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.Contact
import de.ledgerline.app.domain.model.LabeledValue
import de.ledgerline.app.domain.model.PostalAddress
import de.ledgerline.app.domain.workspace.Tags
import de.ledgerline.app.ui.common.ConfirmDialog
import de.ledgerline.app.ui.workspace.LocalFullscreen
import kotlinx.coroutines.launch

/**
 * A single contact: opens read-only (an Edit button flips to the editor); a brand-new
 * contact opens straight in edit mode. Mirrors the web "detail opens read-only, edit via
 * button" flow. Saves on the Save action and on back (when in edit mode).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    contact: Contact,
    isNew: Boolean,
    loadAvatar: suspend (Contact) -> Bitmap?,
    onSave: (Contact) -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onPickAvatar: (ByteArray) -> Unit,
    onRemoveAvatar: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }

    var editing by remember(contact.id) { mutableStateOf(isNew) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (editing) {
        ContactEditor(
            contact = contact,
            loadAvatar = loadAvatar,
            onSave = { updated -> onSave(updated); editing = false },
            onCancel = { if (isNew) onBack() else editing = false },
            onPickAvatar = onPickAvatar,
            onRemoveAvatar = onRemoveAvatar,
            onBack = onBack,
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_contacts)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (contact.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                            stringResource(R.string.contact_favorite),
                            tint = if (contact.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Outlined.Edit, stringResource(R.string.contact_edit))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.contact_delete))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ContactAvatar(contact, loadAvatar, 96.dp)
            Text(
                contactDisplayName(contact).ifBlank { stringResource(R.string.contact_untitled) },
                style = MaterialTheme.typography.headlineSmall,
            )
            val subtitle = listOf(contact.title, contact.org).filter { it.isNotBlank() }.joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                contact.emails.filter { it.value.isNotBlank() }.forEach {
                    DetailLine(stringResource(R.string.contact_email), it.value, it.type)
                }
                contact.phones.filter { it.value.isNotBlank() }.forEach {
                    DetailLine(stringResource(R.string.contact_phone), it.value, it.type)
                }
                contact.impp.filter { it.value.isNotBlank() }.forEach { DetailLine("IM", it.value, it.type) }
                contact.urls.filter { it.value.isNotBlank() }.forEach { DetailLine("URL", it.value, it.type) }
                contact.addresses.forEach { a ->
                    val lines = listOf(
                        a.street,
                        listOf(a.zip, a.city).filter { it.isNotBlank() }.joinToString(" "),
                        a.region, a.country,
                    ).filter { it.isNotBlank() }
                    if (lines.isNotEmpty()) DetailLine(stringResource(R.string.contact_address), lines.joinToString("\n"), a.type)
                }
                if (contact.bday.isNotBlank()) DetailLine(stringResource(R.string.contact_bday), contact.bday, null)
                if (contact.note.isNotBlank()) DetailLine(stringResource(R.string.contact_note), contact.note, null)
                if (contact.categories.isNotEmpty()) DetailLine(stringResource(R.string.tags_hint), contact.categories.joinToString(", "), null)
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            message = stringResource(R.string.contact_delete),
            confirmLabel = stringResource(R.string.contact_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String, type: String?) {
    Column(Modifier.fillMaxWidth()) {
        val head = if (type.isNullOrBlank()) label else "$label · ${typeLabel(type)}"
        Text(head, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun typeLabel(type: String): String = when (type) {
    "home" -> stringResource(R.string.type_home)
    "work" -> stringResource(R.string.type_work)
    "cell" -> stringResource(R.string.type_cell)
    else -> stringResource(R.string.type_other)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactEditor(
    contact: Contact,
    loadAvatar: suspend (Contact) -> Bitmap?,
    onSave: (Contact) -> Unit,
    onCancel: () -> Unit,
    onPickAvatar: (ByteArray) -> Unit,
    onRemoveAvatar: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var first by remember(contact.id) { mutableStateOf(contact.first) }
    var last by remember(contact.id) { mutableStateOf(contact.last) }
    var org by remember(contact.id) { mutableStateOf(contact.org) }
    var department by remember(contact.id) { mutableStateOf(contact.department) }
    var title by remember(contact.id) { mutableStateOf(contact.title) }
    var role by remember(contact.id) { mutableStateOf(contact.role) }
    var nickname by remember(contact.id) { mutableStateOf(contact.nickname) }
    var bday by remember(contact.id) { mutableStateOf(contact.bday) }
    var anniversary by remember(contact.id) { mutableStateOf(contact.anniversary) }
    var note by remember(contact.id) { mutableStateOf(contact.note) }
    var tagsText by remember(contact.id) { mutableStateOf(Tags.formatTags(contact.categories)) }

    val emails = remember(contact.id) { contact.emails.map { EditableLabeled(it.value, it.type) }.toMutableStateList() }
    val phones = remember(contact.id) { contact.phones.map { EditableLabeled(it.value, it.type) }.toMutableStateList() }
    val impp = remember(contact.id) { contact.impp.map { EditableLabeled(it.value, it.type) }.toMutableStateList() }
    val urls = remember(contact.id) { contact.urls.map { EditableLabeled(it.value, it.type) }.toMutableStateList() }
    val addresses = remember(contact.id) { contact.addresses.map { EditableAddress(it) }.toMutableStateList() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) onPickAvatar(bytes)
        }
    }

    fun collect(): Contact = contact.copy(
        first = first.trim(), last = last.trim(),
        org = org.trim(), department = department.trim(), title = title.trim(), role = role.trim(),
        nickname = nickname.trim(),
        emails = emails.filter { it.value.isNotBlank() }.map { LabeledValue(it.value.trim(), it.type) },
        phones = phones.filter { it.value.isNotBlank() }.map { LabeledValue(it.value.trim(), it.type) },
        impp = impp.filter { it.value.isNotBlank() }.map { LabeledValue(it.value.trim(), it.type) },
        urls = urls.filter { it.value.isNotBlank() }.map { LabeledValue(it.value.trim(), it.type) },
        addresses = addresses.map { it.toModel() }.filter { a ->
            a.street.isNotBlank() || a.city.isNotBlank() || a.zip.isNotBlank() || a.country.isNotBlank() || a.region.isNotBlank()
        },
        bday = bday.trim(), anniversary = anniversary.trim(),
        note = note.trim(),
        categories = Tags.parseTags(tagsText),
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (contact.fn.isBlank() && contact.first.isBlank()) stringResource(R.string.contact_new) else stringResource(R.string.contact_edit)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel))
                    }
                },
                actions = {
                    IconButton(onClick = { onSave(collect()) }) {
                        Icon(Icons.Outlined.Check, stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ContactAvatar(contact, loadAvatar, 72.dp)
                Column {
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(stringResource(R.string.contact_avatar_pick))
                    }
                    if (contact.avatarRef != null) {
                        TextButton(onClick = onRemoveAvatar) { Text(stringResource(R.string.contact_avatar_remove)) }
                    }
                }
            }

            Field(first, { first = it }, R.string.contact_first)
            Field(last, { last = it }, R.string.contact_last)
            Field(nickname, { nickname = it }, R.string.contact_nickname)
            Field(org, { org = it }, R.string.contact_org)
            Field(department, { department = it }, R.string.contact_department)
            Field(title, { title = it }, R.string.contact_title)
            Field(role, { role = it }, R.string.contact_role)

            RepeatableSection(
                header = stringResource(R.string.contact_email), rows = emails, addLabel = R.string.contact_add_email,
                keyboardType = KeyboardType.Email, onAdd = { emails.add(EditableLabeled("", "home")) },
            )
            RepeatableSection(
                header = stringResource(R.string.contact_phone), rows = phones, addLabel = R.string.contact_add_phone,
                keyboardType = KeyboardType.Phone, onAdd = { phones.add(EditableLabeled("", "cell")) },
            )
            RepeatableSection(
                header = "IM", rows = impp, addLabel = R.string.contact_add_email,
                keyboardType = KeyboardType.Text, onAdd = { impp.add(EditableLabeled("", "home")) },
            )
            RepeatableSection(
                header = "URL", rows = urls, addLabel = R.string.contact_add_email,
                keyboardType = KeyboardType.Uri, onAdd = { urls.add(EditableLabeled("", "home")) },
            )

            Text(stringResource(R.string.contact_address), style = MaterialTheme.typography.labelLarge)
            addresses.forEachIndexed { i, a ->
                AddressRows(a, onRemove = { addresses.removeAt(i) })
            }
            TextButton(onClick = { addresses.add(EditableAddress(PostalAddress())) }) {
                Icon(Icons.Outlined.Add, null, Modifier.padding(end = 4.dp)); Text(stringResource(R.string.contact_add_address))
            }

            Field(bday, { bday = it }, R.string.contact_bday)
            Field(anniversary, { anniversary = it }, R.string.contact_anniversary)
            Field(note, { note = it }, R.string.contact_note, singleLine = false)
            Field(tagsText, { tagsText = it }, R.string.tags_hint)
        }
    }
}

/** Mutable editor state for a labeled (value + type) field. */
private class EditableLabeled(value: String, type: String) {
    var value by mutableStateOf(value)
    var type by mutableStateOf(type)
}

/** Mutable editor state for a postal address. */
private class EditableAddress(a: PostalAddress) {
    var street by mutableStateOf(a.street)
    var city by mutableStateOf(a.city)
    var region by mutableStateOf(a.region)
    var zip by mutableStateOf(a.zip)
    var country by mutableStateOf(a.country)
    var type by mutableStateOf(a.type)
    fun toModel() = PostalAddress(street.trim(), city.trim(), region.trim(), zip.trim(), country.trim(), type)
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatableSection(
    header: String,
    rows: SnapshotStateList<EditableLabeled>,
    addLabel: Int,
    keyboardType: KeyboardType,
    onAdd: () -> Unit,
) {
    Text(header, style = MaterialTheme.typography.labelLarge)
    rows.forEachIndexed { i, row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = row.value,
                onValueChange = { row.value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.weight(1f),
            )
            TypeDropdown(row.type, onSelect = { row.type = it })
            IconButton(onClick = { rows.removeAt(i) }) {
                Icon(Icons.Outlined.Close, stringResource(R.string.action_delete_forever))
            }
        }
    }
    TextButton(onClick = onAdd) {
        Icon(Icons.Outlined.Add, null, Modifier.padding(end = 4.dp)); Text(stringResource(addLabel))
    }
}

@Composable
private fun AddressRows(a: EditableAddress, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(a.street, { a.street = it }, label = { Text(stringResource(R.string.address_street)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(a.zip, { a.zip = it }, label = { Text(stringResource(R.string.address_zip)) }, singleLine = true, modifier = Modifier.width(110.dp))
            OutlinedTextField(a.city, { a.city = it }, label = { Text(stringResource(R.string.address_city)) }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(a.region, { a.region = it }, label = { Text(stringResource(R.string.address_region)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(a.country, { a.country = it }, label = { Text(stringResource(R.string.address_country)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeDropdown(a.type, onSelect = { a.type = it })
            TextButton(onClick = onRemove) { Text(stringResource(R.string.action_delete_forever)) }
        }
    }
}

@Composable
private fun TypeDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(typeLabel(selected)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("home", "work", "cell", "other").forEach { t ->
                DropdownMenuItem(text = { Text(typeLabel(t)) }, onClick = { onSelect(t); expanded = false })
            }
        }
    }
}
