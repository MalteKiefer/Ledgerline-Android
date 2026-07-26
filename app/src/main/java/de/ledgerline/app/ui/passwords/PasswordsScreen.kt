package de.ledgerline.app.ui.passwords

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

/**
 * The leading avatar for a secret: the site favicon when one is available (stored `icon` or
 * fetched for the item's domain), otherwise the tinted type [IconChip]. The favicon load is
 * async + cached in the ViewModel, so a missing/slow icon simply shows the type chip.
 */
@Composable
private fun SecretAvatar(item: SecretItem, vm: PasswordsViewModel) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.id, item.icon) {
        value = vm.iconFor(item)
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(Brand.chipSize)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(Brand.chipRadius))
                .background(Color.White),
        )
    } else {
        IconChip(typeIcon(item.type), tint = typeTint(item.type))
    }
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
        is PwRoute.Edit -> PwEdit(r.item, vm, onCancel = { route = PwRoute.List }, onSave = { vm.upsert(it) { ok -> if (ok) route = PwRoute.List } })
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
                val folders by vm.folders.collectAsStateWithLifecycle()
                val folderFilter by vm.folderFilter.collectAsStateWithLifecycle()
                if (folders.isNotEmpty()) {
                    var folderMenu by remember { mutableStateOf(false) }
                    Box {
                        FilterChip(
                            selected = folderFilter != null,
                            onClick = { folderMenu = true },
                            label = { Text(folders.firstOrNull { it.id == folderFilter }?.name ?: stringResource(de.ledgerline.app.R.string.pw_folder)) },
                            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(de.ledgerline.app.R.string.pw_folder_none)) }, onClick = { vm.setFolderFilter(null); folderMenu = false })
                            folders.forEach { f -> DropdownMenuItem(text = { Text(f.name) }, onClick = { vm.setFolderFilter(f.id); folderMenu = false }) }
                        }
                    }
                }
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
                                leadingContent = { SecretAvatar(s, vm) },
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
                Icon(Icons.Filled.Add, contentDescription = stringResource(de.ledgerline.app.R.string.cd_add))
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
                    Icon(if (item.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = stringResource(de.ledgerline.app.R.string.action_favorite))
                }
                if (item.isTrashed) {
                    IconButton(onClick = { vm.restore(item.id); onBack() }) { Icon(Icons.Outlined.Restore, contentDescription = stringResource(de.ledgerline.app.R.string.action_restore)) }
                    IconButton(onClick = { vm.deleteForever(item.id); onBack() }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(de.ledgerline.app.R.string.action_delete)) }
                } else {
                    IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(de.ledgerline.app.R.string.cd_edit)) }
                    IconButton(onClick = { vm.trash(item.id); onBack() }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(de.ledgerline.app.R.string.cd_trash)) }
                }
            })
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(16.dp),
        ) {
            (SecretTypes.fields[item.type] ?: emptyList()).forEach { key ->
                when (key) {
                    "urls" -> SecretFields.urls(item).forEach { u -> FieldRow(fieldLabel("urls"), u, secret = false, context) }
                    "totp" -> SecretFields.str(item, "totp").takeIf { it.isNotBlank() }?.let { TotpRow(it, context) }
                    else -> SecretFields.str(item, key).takeIf { it.isNotBlank() }?.let { v -> FieldRow(fieldLabel(key), v, secret = SecretTypes.isSecretKey(key), context) }
                }
            }
            item.custom.forEach { c -> FieldRow(c.label.ifBlank { "Field" }, c.value, secret = c.kind == "secret", context) }
            vm.tfaSetupUrl(item)?.let { url -> TfaOfferRow(url, context) }
            if (SecretFields.str(item, "password").isNotBlank()) BreachRow(item, vm)
            PasskeysSection(item, vm)
            if (item.versions.isNotEmpty()) VersionHistory(item, vm)
        }
    }
}

/**
 * In-app passkey management for a login: lists the passkeys embedded in this item
 * (rpId + userName) with a per-entry delete. Standalone `passkey` items are managed as
 * ordinary secrets (list/detail/trash); this surfaces the embedded ones the detail otherwise hides.
 */
@Composable
private fun PasskeysSection(item: SecretItem, vm: PasswordsViewModel) {
    val passkeys = remember(item) { de.ledgerline.app.core.passkey.PasskeyStore.embedded(item) }
    if (passkeys.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            stringResource(de.ledgerline.app.R.string.pw_passkeys),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
        )
        passkeys.forEach { pk ->
            ListItem(
                headlineContent = { Text(pk.userName.ifBlank { pk.rpId }) },
                supportingContent = { Text(pk.rpId) },
                leadingContent = { Icon(Icons.Outlined.Fingerprint, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { vm.deleteEmbeddedPasskey(item.id, pk.credentialIdB64) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(de.ledgerline.app.R.string.action_delete))
                    }
                },
            )
        }
    }
}

/** Opt-in HIBP breach check (k-anonymity — only a 5-hex SHA-1 prefix is sent to the server). */
@Composable
private fun BreachRow(item: SecretItem, vm: PasswordsViewModel) {
    var state by remember(item.id) { mutableStateOf<Int?>(-1) } // -1 = not checked, null = failed/none
    var loading by remember(item.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        when {
            state == -1 -> TextButton(onClick = {
                loading = true; vm.checkBreach(item) { c -> state = c; loading = false }
            }, enabled = !loading) { Text(stringResource(de.ledgerline.app.R.string.pw_breach_check)) }
            state == null -> Text(stringResource(de.ledgerline.app.R.string.pw_breach_error), color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            state == 0 -> Text(stringResource(de.ledgerline.app.R.string.pw_breach_clear), color = Brand.tintGreen)
            else -> Text(stringResource(de.ledgerline.app.R.string.pw_breach_found, state!!), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        }
        Text(stringResource(de.ledgerline.app.R.string.pw_breach_note), style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Password strength bar (web pwScore 0–4). */
@Composable
private fun StrengthBar(pw: String) {
    val score = de.ledgerline.app.core.passwords.PasswordStrength.score(pw)
    val (color, labelRes) = when {
        score >= 4 -> Brand.tintGreen to de.ledgerline.app.R.string.pw_strength_strong
        score == 3 -> Brand.tintTeal to de.ledgerline.app.R.string.pw_strength_good
        score == 2 -> Brand.tintOrange to de.ledgerline.app.R.string.pw_strength_fair
        else -> androidx.compose.material3.MaterialTheme.colorScheme.error to de.ledgerline.app.R.string.pw_strength_weak
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        androidx.compose.material3.LinearProgressIndicator(
            progress = { (score.coerceIn(0, 4)) / 4f },
            color = color,
            modifier = Modifier.weight(1f).height(6.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(labelRes), style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Saved version history with restore. */
@Composable
private fun VersionHistory(item: SecretItem, vm: PasswordsViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(stringResource(de.ledgerline.app.R.string.pw_version_history), style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
        item.versions.take(20).forEach { ver ->
            ListItem(
                headlineContent = { Text(ver.title.ifBlank { "—" }) },
                supportingContent = { Text(ver.at) },
                trailingContent = { TextButton(onClick = { vm.restoreVersion(item, ver) }) { Text(stringResource(de.ledgerline.app.R.string.pw_version_restore)) } },
            )
        }
    }
}

/**
 * "This site offers 2FA" hint for a login without a stored TOTP whose domain is in the
 * 2fa.directory dataset. Tapping "Set up" opens the site's setup-docs URL in the browser.
 */
@Composable
private fun TfaOfferRow(setupUrl: String, context: android.content.Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(Brand.chipRadius))
            .background(Brand.tintTeal.copy(alpha = 0.14f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Shield, contentDescription = null, tint = Brand.tintTeal)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(de.ledgerline.app.R.string.pw_tfa_available),
            modifier = Modifier.weight(1f),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = {
            runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(setupUrl)))
            }
        }) { Text(stringResource(de.ledgerline.app.R.string.pw_tfa_setup)) }
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
                Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = stringResource(de.ledgerline.app.R.string.cd_reveal))
            }
            IconButton(onClick = { if (secret) SecureClipboard.copySensitive(context, label, value) else SecureClipboard.copyPlain(context, label, value) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(de.ledgerline.app.R.string.cd_copy))
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
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(de.ledgerline.app.R.string.cd_copy_code))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PwEdit(item: SecretItem, vm: PasswordsViewModel, onCancel: () -> Unit, onSave: (SecretItem) -> Unit) {
    var title by remember { mutableStateOf(item.title) }
    val keys = SecretTypes.fields[item.type] ?: listOf("note")
    val values = remember { keys.filter { it != "urls" }.associateWith { mutableStateOf(SecretFields.str(item, it)) }.toMutableMap() }
    var url by remember { mutableStateOf(SecretFields.urls(item).firstOrNull().orEmpty()) }
    var genSheet by remember { mutableStateOf(false) }
    val folders by vm.folders.collectAsStateWithLifecycle()
    // New items default to the current folder filter or the first folder (web mandates a folder).
    var folder by remember { mutableStateOf(item.folder ?: vm.folderFilter.value ?: vm.folders.value.firstOrNull()?.id) }
    val tags = remember { item.tags.toMutableStateList() }
    val custom = remember { item.custom.toMutableStateList() }

    AppScaffold(
        topBar = {
            AppTopBar(if (item.title.isBlank()) "New ${typeLabel(item.type)}" else "Edit", onBack = onCancel, actions = {
                TextButton(onClick = {
                    // Normalise a pasted otpauth:// TOTP URI to the bare base32 secret we store.
                    val normalized = values.mapValues { (k, st) ->
                        if (k == "totp") de.ledgerline.app.core.passwords.Totp.normalizeSecret(st.value) else st.value
                    }
                    val fields = SecretFields.build(item.fields, item.type, normalized, listOf(url))
                    onSave(item.copy(
                        title = title.ifBlank { typeLabel(item.type) },
                        fields = fields, folder = folder, tags = tags.toList(),
                        custom = custom.filter { it.label.isNotBlank() || it.value.isNotBlank() }.toList(),
                    ))
                }) { Text("Save") }
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp)) {
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
                            keyboardOptions = if (secret) de.ledgerline.app.ui.common.secretKeyboardOptions() else KeyboardOptions.Default,
                            trailingIcon = if (key == "password") {
                                { IconButton(onClick = { genSheet = true }) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(de.ledgerline.app.R.string.cd_generate)) } }
                            } else null,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                        if (key == "password" && v.value.isNotEmpty()) StrengthBar(v.value)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FolderField(folders, folder, onSelect = { folder = it }, onCreate = { name -> vm.createFolder(name) { id -> if (id != null) folder = id } })
            TagsField(tags)
            CustomFieldsEditor(custom)
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

/** Folder picker: existing password folders + "None" + inline "New folder…". */
@Composable
private fun FolderField(
    folders: List<de.ledgerline.app.domain.model.SecretFolder>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onCreate: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var newDialog by remember { mutableStateOf(false) }
    val name = folders.firstOrNull { it.id == selected }?.name ?: stringResource(de.ledgerline.app.R.string.pw_folder_none)
    Box {
        OutlinedTextField(
            value = name, onValueChange = {}, readOnly = true,
            label = { Text(stringResource(de.ledgerline.app.R.string.pw_folder)) },
            trailingIcon = { IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.Folder, contentDescription = null) } },
            modifier = Modifier.fillMaxWidth().clickable { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(de.ledgerline.app.R.string.pw_folder_none)) }, onClick = { onSelect(null); menu = false })
            folders.forEach { f -> DropdownMenuItem(text = { Text(f.name) }, onClick = { onSelect(f.id); menu = false }) }
            DropdownMenuItem(text = { Text(stringResource(de.ledgerline.app.R.string.pw_folder_new)) }, onClick = { menu = false; newDialog = true })
        }
    }
    if (newDialog) {
        var input by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { newDialog = false },
            confirmButton = { TextButton(onClick = { if (input.isNotBlank()) onCreate(input.trim()); newDialog = false }) { Text(stringResource(de.ledgerline.app.R.string.pw_folder_create)) } },
            dismissButton = { TextButton(onClick = { newDialog = false }) { Text(stringResource(de.ledgerline.app.R.string.action_cancel)) } },
            title = { Text(stringResource(de.ledgerline.app.R.string.pw_folder_new)) },
            text = { OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, label = { Text(stringResource(de.ledgerline.app.R.string.pw_folder)) }) },
        )
    }
}

/** Editable tag chips. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagsField(tags: MutableList<String>) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(stringResource(de.ledgerline.app.R.string.pw_tags), style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.toList().forEach { t ->
                androidx.compose.material3.InputChip(
                    selected = false, onClick = {}, label = { Text(t) },
                    trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = stringResource(de.ledgerline.app.R.string.cd_remove), modifier = Modifier.size(16.dp).clickable { tags.remove(t) }) },
                )
            }
        }
        OutlinedTextField(
            value = input, onValueChange = { input = it }, singleLine = true,
            label = { Text(stringResource(de.ledgerline.app.R.string.pw_tag_add)) },
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (input.isNotBlank()) { tags.add(input.trim()); input = "" } }),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** Editable custom fields (label + value, add/remove). */
@Composable
private fun CustomFieldsEditor(custom: MutableList<de.ledgerline.app.domain.model.CustomField>) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(stringResource(de.ledgerline.app.R.string.pw_custom_fields), style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        custom.forEachIndexed { i, cf ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                OutlinedTextField(
                    value = cf.label, onValueChange = { custom[i] = cf.copy(label = it) },
                    label = { Text(stringResource(de.ledgerline.app.R.string.pw_custom_label)) },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = cf.value, onValueChange = { custom[i] = cf.copy(value = it) },
                    label = { Text(stringResource(de.ledgerline.app.R.string.pw_custom_value)) },
                    singleLine = true, modifier = Modifier.weight(1f),
                    visualTransformation = if (cf.kind == "secret") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    // §3.3: a secret custom field must be excluded from IME learning too.
                    keyboardOptions = if (cf.kind == "secret") de.ledgerline.app.ui.common.secretKeyboardOptions() else KeyboardOptions.Default,
                )
                IconButton(onClick = { custom.removeAt(i) }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(de.ledgerline.app.R.string.cd_remove)) }
            }
        }
        TextButton(onClick = { custom.add(de.ledgerline.app.domain.model.CustomField()) }) {
            Icon(Icons.Filled.Add, contentDescription = null); Spacer(Modifier.width(4.dp)); Text(stringResource(de.ledgerline.app.R.string.pw_custom_add))
        }
    }
}
