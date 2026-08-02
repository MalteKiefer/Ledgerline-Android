package de.ledgerline.app.ui.workspace.contacts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.ledgerline.app.R
import de.ledgerline.app.core.Dates
import de.ledgerline.app.data.DateFormatPref
import de.ledgerline.app.domain.model.Contact
import de.ledgerline.app.domain.model.LabeledValue
import de.ledgerline.app.domain.model.PostalAddress
import de.ledgerline.app.domain.workspace.Tags
import de.ledgerline.app.ui.common.ConfirmDialog
import de.ledgerline.app.ui.common.openUrl
import de.ledgerline.app.ui.workspace.LocalFullscreen
import de.ledgerline.app.ui.workspace.common.DetailQuickAction
import de.ledgerline.app.ui.workspace.common.InfoCard
import de.ledgerline.app.ui.workspace.common.InfoRow
import de.ledgerline.app.ui.workspace.common.RowDivider
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
    dateFormat: DateFormatPref = DateFormatPref.SYSTEM,
    linkChooser: Boolean = true,
    nameOrder: de.ledgerline.app.data.ContactNameOrder = de.ledgerline.app.data.ContactNameOrder.LAST_FIRST,
    modifier: Modifier = Modifier,
) {
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }
    val ctx = LocalContext.current

    var editing by remember(contact.id) { mutableStateOf(isNew) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }

    // Take an avatar photo in-app (in-memory JPEG, no disk).
    if (showCamera) {
        BackHandler { showCamera = false }
        de.ledgerline.app.ui.gallery.CameraCaptureScreen(
            onCaptured = { bytes, _, _ -> onPickAvatar(bytes); showCamera = false },
            onBack = { showCamera = false },
        )
        return
    }

    if (editing) {
        ContactEditor(
            contact = contact,
            loadAvatar = loadAvatar,
            onSave = { updated -> onSave(updated); editing = false },
            onCancel = { if (isNew) onBack() else editing = false },
            onPickAvatar = onPickAvatar,
            onTakePhoto = { showCamera = true },
            onRemoveAvatar = onRemoveAvatar,
            onBack = onBack,
            modifier = modifier,
        )
        return
    }

    // System back closes the detail (returns to the list), not the whole module.
    BackHandler { onBack() }

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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            // --- Hero ---
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(top = 12.dp, bottom = 20.dp)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ContactAvatar(contact, loadAvatar, 112.dp)
                Text(
                    contactDisplayName(contact, nameOrder).ifBlank { stringResource(R.string.contact_untitled) },
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                val subtitle = listOf(contact.title, contact.org).filter { it.isNotBlank() }.joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (contact.nickname.isNotBlank()) {
                    Text(
                        "“${contact.nickname}”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --- Quick actions ---
            val firstPhone = contact.phones.firstOrNull { it.value.isNotBlank() }?.value?.trim()
            val firstEmail = contact.emails.firstOrNull { it.value.isNotBlank() }?.value?.trim()
            val firstUrl = contact.urls.firstOrNull { it.value.isNotBlank() }?.value?.trim()
            val firstAddr = contact.addresses.firstOrNull { a -> !addressQuery(a).isBlank() }
            if (firstPhone != null || firstEmail != null || firstUrl != null || firstAddr != null) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                ) {
                    if (firstPhone != null) {
                        DetailQuickAction(Icons.Outlined.Call, stringResource(R.string.contact_action_call)) {
                            ctx.launch(Intent(Intent.ACTION_DIAL, "tel:$firstPhone".toUri()))
                        }
                        DetailQuickAction(Icons.AutoMirrored.Outlined.Message, stringResource(R.string.contact_action_message)) {
                            ctx.launch(Intent(Intent.ACTION_SENDTO, "smsto:$firstPhone".toUri()))
                        }
                    }
                    if (firstEmail != null) {
                        DetailQuickAction(Icons.Outlined.Email, stringResource(R.string.contact_action_mail)) {
                            ctx.launch(Intent(Intent.ACTION_SENDTO, "mailto:$firstEmail".toUri()))
                        }
                    }
                    if (firstUrl != null) {
                        DetailQuickAction(Icons.Outlined.Language, stringResource(R.string.contact_action_web)) {
                            openUrl(ctx, normalizeUrl(firstUrl), linkChooser)
                        }
                    }
                    if (firstAddr != null) {
                        DetailQuickAction(Icons.Outlined.Place, stringResource(R.string.contact_action_navigate)) {
                            ctx.launch(Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(addressQuery(firstAddr))}".toUri()))
                        }
                    }
                }
            }

            // --- Grouped info cards ---
            val phones = contact.phones.filter { it.value.isNotBlank() }
            if (phones.isNotEmpty()) InfoCard {
                phones.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    InfoRow(Icons.Outlined.Call, p.value, typeLabel(p.type)) {
                        ctx.launch(Intent(Intent.ACTION_DIAL, "tel:${p.value.trim()}".toUri()))
                    }
                }
            }
            val emails = contact.emails.filter { it.value.isNotBlank() }
            if (emails.isNotEmpty()) InfoCard {
                emails.forEachIndexed { i, e ->
                    if (i > 0) RowDivider()
                    InfoRow(Icons.Outlined.Email, e.value, typeLabel(e.type)) {
                        ctx.launch(Intent(Intent.ACTION_SENDTO, "mailto:${e.value.trim()}".toUri()))
                    }
                }
            }
            val addresses = contact.addresses.filter { a -> !addressQuery(a).isBlank() }
            if (addresses.isNotEmpty()) InfoCard {
                addresses.forEachIndexed { i, a ->
                    if (i > 0) RowDivider()
                    val lines = listOf(
                        a.street,
                        listOf(a.zip, a.city).filter { it.isNotBlank() }.joinToString(" "),
                        a.region, a.country,
                    ).filter { it.isNotBlank() }
                    InfoRow(Icons.Outlined.Place, lines.joinToString("\n"), typeLabel(a.type)) {
                        ctx.launch(Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(addressQuery(a))}".toUri()))
                    }
                }
            }
            val web = contact.urls.filter { it.value.isNotBlank() }
            val ims = contact.impp.filter { it.value.isNotBlank() }
            if (web.isNotEmpty() || ims.isNotEmpty()) InfoCard {
                web.forEachIndexed { i, u ->
                    if (i > 0) RowDivider()
                    InfoRow(Icons.Outlined.Language, u.value, "URL") { openUrl(ctx, normalizeUrl(u.value.trim()), linkChooser) }
                }
                ims.forEachIndexed { i, m ->
                    if (i > 0 || web.isNotEmpty()) RowDivider()
                    InfoRow(Icons.AutoMirrored.Outlined.Message, m.value, "IM")
                }
            }
            val hasInfo = contact.bday.isNotBlank() || contact.anniversary.isNotBlank() || contact.note.isNotBlank()
            if (hasInfo) InfoCard {
                var shown = false
                if (contact.bday.isNotBlank()) {
                    InfoRow(Icons.Outlined.Cake, Dates.format(contact.bday, dateFormat), stringResource(R.string.contact_bday))
                    shown = true
                }
                if (contact.anniversary.isNotBlank()) {
                    if (shown) RowDivider()
                    InfoRow(Icons.Outlined.Favorite, Dates.format(contact.anniversary, dateFormat), stringResource(R.string.contact_anniversary))
                    shown = true
                }
                if (contact.note.isNotBlank()) {
                    if (shown) RowDivider()
                    InfoRow(Icons.Outlined.Notes, contact.note, stringResource(R.string.contact_note))
                }
            }
            if (contact.categories.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    contact.categories.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                }
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

/** Fire an intent, swallowing failures so a missing handler never crashes the screen. */
private fun Context.launch(intent: Intent) {
    runCatching { startActivity(intent) }
}

/** Prefix a bare host with https:// so it opens as a web link. */
private fun normalizeUrl(u: String): String = if (u.contains("://")) u else "https://$u"

/** Flatten a postal address into a single geo-query string. */
private fun addressQuery(a: PostalAddress): String =
    listOf(a.street, a.zip, a.city, a.region, a.country).filter { it.isNotBlank() }.joinToString(", ")

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
    onTakePhoto: () -> Unit,
    onRemoveAvatar: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler { onCancel() }

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
    var tags by remember(contact.id) { mutableStateOf(contact.categories) }
    var tagDraft by remember(contact.id) { mutableStateOf("") }

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
        categories = Tags.mergeDraft(tags, tagDraft),
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
                    TextButton(onClick = onTakePhoto) { Text(stringResource(R.string.contact_avatar_camera)) }
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
            de.ledgerline.app.ui.workspace.common.TagInput(
                tags = tags,
                onTagsChange = { tags = it },
                draft = tagDraft,
                onDraftChange = { tagDraft = it },
                modifier = Modifier.fillMaxWidth(),
            )
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
