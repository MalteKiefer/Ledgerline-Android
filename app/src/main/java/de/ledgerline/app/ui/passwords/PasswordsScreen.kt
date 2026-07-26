package de.ledgerline.app.ui.passwords

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.core.security.SecureClipboard
import de.ledgerline.app.domain.model.SecretFields
import de.ledgerline.app.domain.model.SecretItem
import de.ledgerline.app.domain.model.SecretTypes
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.IconChip

private sealed interface PwRoute {
    data object List : PwRoute
    data class Detail(val id: String) : PwRoute
    data class Edit(val item: SecretItem) : PwRoute
}

fun typeIcon(type: String): ImageVector = when (type) {
    "login" -> Icons.Outlined.Person
    "password" -> Icons.Outlined.Password
    "card" -> Icons.Outlined.CreditCard
    "wifi" -> Icons.Outlined.Wifi
    "license" -> Icons.Outlined.VpnKey
    "server" -> Icons.Outlined.Dns
    "identity" -> Icons.Outlined.Badge
    "passkey" -> Icons.Outlined.Fingerprint
    else -> Icons.Outlined.Description
}

private fun typeTint(type: String): Color = when (type) {
    "login" -> Brand.tintBlue
    "card" -> Brand.tintGreen
    "wifi" -> Brand.tintTeal
    "server" -> Brand.tintViolet
    "identity" -> Brand.tintOrange
    else -> Brand.tintGray
}

/** Readable label for a field key (kept simple; localised chrome uses string resources). */
fun fieldLabel(key: String): String = when (key) {
    "username" -> "Username"; "password" -> "Password"; "urls" -> "Website"; "totp" -> "One-time code (TOTP)"
    "note" -> "Note"; "cardholder" -> "Cardholder"; "number" -> "Card number"; "expiry" -> "Expiry"
    "cvv" -> "CVV"; "pin" -> "PIN"; "ssid" -> "Network (SSID)"; "security" -> "Security"; "hidden" -> "Hidden"
    "product" -> "Product"; "licensekey" -> "License key"; "owner" -> "Owner"; "email" -> "Email"
    "host" -> "Host"; "port" -> "Port"; "firstName" -> "First name"; "lastName" -> "Last name"
    "phone" -> "Phone"; "company" -> "Company"; "street" -> "Street"; "city" -> "City"; "state" -> "State"
    "zip" -> "ZIP"; "country" -> "Country"; "rpId" -> "Relying party"; "userName" -> "User"
    "userDisplayName" -> "Display name"
    else -> key.replaceFirstChar { it.uppercase() }
}

private fun typeLabel(type: String): String = when (type) {
    "login" -> "Login"; "password" -> "Password"; "card" -> "Payment card"; "wifi" -> "Wi-Fi"
    "license" -> "Software license"; "server" -> "Server"; "identity" -> "Identity"
    "secure_note" -> "Secure note"; "passkey" -> "Passkey"; else -> type
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordsScreen(modifier: Modifier = Modifier, vm: PasswordsViewModel = hiltViewModel()) {
    var route by remember { mutableStateOf<PwRoute>(PwRoute.List) }
    BackHandler(enabled = route !is PwRoute.List) { route = PwRoute.List }

    when (val r = route) {
        is PwRoute.List -> PwList(vm, modifier, onOpen = { route = PwRoute.Detail(it) }, onNew = { route = PwRoute.Edit(vm.draft(it)) })
        is PwRoute.Detail -> {
            val item = vm.secretById(r.id)
            if (item == null) { route = PwRoute.List } else {
                PwDetail(item, vm, onBack = { route = PwRoute.List }, onEdit = { route = PwRoute.Edit(item) })
            }
        }
        is PwRoute.Edit -> PwEdit(r.item, onCancel = { route = PwRoute.List }, onSave = { vm.upsert(it) { ok -> if (ok) route = PwRoute.List } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PwList(vm: PasswordsViewModel, modifier: Modifier, onOpen: (String) -> Unit, onNew: (String) -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val typeFilter by vm.typeFilter.collectAsStateWithLifecycle()
    val showTrash by vm.showTrash.collectAsStateWithLifecycle()
    val favOnly by vm.favoritesOnly.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = vm::setQuery,
                label = { Text("Search passwords") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = favOnly, onClick = { vm.toggleFavoritesOnly() }, label = { Text("Favorites") })
                FilterChip(selected = showTrash, onClick = { vm.setShowTrash(!showTrash) }, label = { Text(if (ui.trashCount > 0) "Trash (${ui.trashCount})" else "Trash") })
                if (typeFilter != null) AssistChip(onClick = { vm.setTypeFilter(null) }, label = { Text(typeLabel(typeFilter!!)) })
            }
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::reload,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (ui.secrets.isEmpty()) {
                        item {
                            Box(Modifier.fillParentMaxSize().padding(24.dp), Alignment.Center) {
                                Text(
                                    if (ui.loading) "Loading…" else if (ui.error) "Couldn't load — pull to retry" else "No passwords yet — pull to refresh",
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(ui.secrets, key = { it.id }) { s ->
                            ListItem(
                                modifier = Modifier.clickable { onOpen(s.id) },
                                leadingContent = { IconChip(typeIcon(s.type), tint = typeTint(s.type)) },
                                headlineContent = { Text(s.title.ifBlank { typeLabel(s.type) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { SecretFields.subtitle(s).takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                                trailingContent = { if (s.favorite) Icon(Icons.Outlined.Star, contentDescription = null, tint = Brand.accent) },
                            )
                        }
                    }
                }
            }
        }
        if (!showTrash) {
            FloatingActionButton(onClick = { picker = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
        }
    }

    if (picker) {
        ModalBottomSheet(onDismissRequest = { picker = false }) {
            Text("New item", modifier = Modifier.padding(16.dp), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            SecretTypes.creatable.forEach { t ->
                ListItem(
                    modifier = Modifier.clickable { picker = false; onNew(t) },
                    leadingContent = { IconChip(typeIcon(t), tint = typeTint(t)) },
                    headlineContent = { Text(typeLabel(t)) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PwDetail(item: SecretItem, vm: PasswordsViewModel, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    AppScaffold(
        topBar = {
            AppTopBar(item.title.ifBlank { typeLabel(item.type) }, onBack = onBack, actions = {
                IconButton(onClick = { vm.toggleFavorite(item.id) }) {
                    Icon(if (item.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = "Favorite")
                }
                if (item.isTrashed) {
                    IconButton(onClick = { vm.restore(item.id); onBack() }) { Icon(Icons.Outlined.Restore, contentDescription = "Restore") }
                    IconButton(onClick = { vm.deleteForever(item.id); onBack() }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
                } else {
                    IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                    IconButton(onClick = { vm.trash(item.id); onBack() }) { Icon(Icons.Outlined.Delete, contentDescription = "Trash") }
                }
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            (SecretTypes.fields[item.type] ?: emptyList()).forEach { key ->
                when (key) {
                    "urls" -> SecretFields.urls(item).forEach { u -> FieldRow(fieldLabel("urls"), u, secret = false, context) }
                    "totp" -> SecretFields.str(item, "totp").takeIf { it.isNotBlank() }?.let { TotpRow(it, context) }
                    else -> SecretFields.str(item, key).takeIf { it.isNotBlank() }?.let { v -> FieldRow(fieldLabel(key), v, secret = SecretTypes.isSecretKey(key), context) }
                }
            }
            item.custom.forEach { c -> FieldRow(c.label.ifBlank { "Field" }, c.value, secret = c.kind == "secret", context) }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, secret: Boolean, context: android.content.Context) {
    var revealed by remember { mutableStateOf(!secret) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (revealed) value else "•".repeat(minOf(value.length, 12)),
                modifier = Modifier.weight(1f), maxLines = if (revealed) Int.MAX_VALUE else 1,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            )
            if (secret) IconButton(onClick = { revealed = !revealed }) {
                Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = "Reveal")
            }
            IconButton(onClick = { if (secret) SecureClipboard.copySensitive(context, label, value) else SecureClipboard.copyPlain(context, label, value) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy")
            }
        }
    }
}

@Composable
private fun TotpRow(secret: String, context: android.content.Context) {
    var tick by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    androidx.compose.runtime.LaunchedEffect(secret) {
        while (true) { tick = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(1000) }
    }
    val code = remember(tick, secret) { de.ledgerline.app.core.passwords.Totp.code(secret) }
    val remaining = de.ledgerline.app.core.passwords.Totp.secondsRemaining(tick)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(fieldLabel("totp"), style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val display = code?.let { it.substring(0, 3) + " " + it.substring(3) } ?: "——— ———"
            Text(display, modifier = Modifier.weight(1f), style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, color = Brand.accent)
            Text("${remaining}s", style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { code?.let { SecureClipboard.copySensitive(context, "TOTP", it) } }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy code")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PwEdit(item: SecretItem, onCancel: () -> Unit, onSave: (SecretItem) -> Unit) {
    var title by remember { mutableStateOf(item.title) }
    val keys = SecretTypes.fields[item.type] ?: listOf("note")
    val values = remember { keys.filter { it != "urls" }.associateWith { mutableStateOf(SecretFields.str(item, it)) }.toMutableMap() }
    var url by remember { mutableStateOf(SecretFields.urls(item).firstOrNull().orEmpty()) }
    var genSheet by remember { mutableStateOf(false) }

    AppScaffold(
        topBar = {
            AppTopBar(if (item.title.isBlank()) "New ${typeLabel(item.type)}" else "Edit", onBack = onCancel, actions = {
                TextButton(onClick = {
                    val fields = SecretFields.build(item.fields, item.type, values.mapValues { it.value.value }, listOf(url))
                    onSave(item.copy(title = title.ifBlank { typeLabel(item.type) }, fields = fields))
                }) { Text("Save") }
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            keys.forEach { key ->
                when (key) {
                    "urls" -> {
                        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(fieldLabel("urls")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }
                    else -> {
                        val secret = SecretTypes.isSecretKey(key)
                        val v = values.getValue(key)
                        OutlinedTextField(
                            value = v.value, onValueChange = { v.value = it },
                            label = { Text(fieldLabel(key)) },
                            singleLine = key != "note",
                            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                            trailingIcon = if (key == "password") {
                                { IconButton(onClick = { genSheet = true }) { Icon(Icons.Outlined.Refresh, contentDescription = "Generate") } }
                            } else null,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (genSheet) {
        ModalBottomSheet(onDismissRequest = { genSheet = false }) {
            val scope = rememberCoroutineScope()
            var length by remember { mutableStateOf(20) }
            var generated by remember { mutableStateOf(de.ledgerline.app.core.passwords.PasswordGenerator.generate(length)) }
            Column(Modifier.padding(16.dp)) {
                Text("Generate password", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(generated, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, color = Brand.accent)
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { generated = de.ledgerline.app.core.passwords.PasswordGenerator.generate(length) }) { Text("Regenerate") }
                    TextButton(onClick = { values["password"]?.value = generated; genSheet = false }) { Text("Use") }
                }
            }
        }
    }
}
