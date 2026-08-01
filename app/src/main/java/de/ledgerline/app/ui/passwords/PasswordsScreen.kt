package de.ledgerline.app.ui.passwords

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.AssistChip
import androidx.compose.ui.text.withStyle
import de.ledgerline.app.ui.theme.cardSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
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
import de.ledgerline.app.ui.common.listSection
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
private fun SecretAvatar(item: SecretItem, vm: PasswordsViewModel, size: androidx.compose.ui.unit.Dp = Brand.chipSize) {
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
                .size(size)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(Brand.chipRadius))
                .background(Color.White),
        )
    } else {
        IconChip(typeIcon(item.type), tint = typeTint(item.type), size = size)
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
fun PasswordsScreen(modifier: Modifier = Modifier, onMenu: (() -> Unit)? = null, vm: PasswordsViewModel = hiltViewModel()) {
    var route by remember { mutableStateOf<PwRoute>(PwRoute.List) }
    BackHandler(enabled = route !is PwRoute.List) { route = PwRoute.List }

    when (val r = route) {
        is PwRoute.List -> PwList(vm, modifier, onMenu = onMenu, onOpen = { route = PwRoute.Detail(it) }, onNew = { route = PwRoute.Edit(vm.draft(it)) })
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
private fun PwList(vm: PasswordsViewModel, modifier: Modifier, onMenu: (() -> Unit)?, onOpen: (String) -> Unit, onNew: (String) -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val typeFilter by vm.typeFilter.collectAsStateWithLifecycle()
    val showTrash by vm.showTrash.collectAsStateWithLifecycle()
    val favOnly by vm.favoritesOnly.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }

    // Load on entry (offline-first: cache shows immediately, then refreshes) so the vault
    // is populated without a manual pull-to-refresh.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.reload() }

    val folders by vm.folders.collectAsStateWithLifecycle()
    val folderFilter by vm.folderFilter.collectAsStateWithLifecycle()
    var searchActive by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    if (showHistory) {
        de.ledgerline.app.ui.common.StoreHistoryDialog(
            onDismiss = { showHistory = false },
            load = { vm.historyVersions() },
            recover = { vm.recoverVersion(it) },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                de.ledgerline.app.ui.common.AppTopBar(
                    title = stringResource(de.ledgerline.app.R.string.tab_passwords),
                    onMenu = onMenu,
                    actions = {
                        IconButton(onClick = { searchActive = !searchActive; if (!searchActive) vm.setQuery("") }) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                tint = if (searchActive) Brand.accent else LocalContentColor.current,
                            )
                        }
                        Box {
                            IconButton(onClick = { overflow = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(de.ledgerline.app.R.string.action_more))
                            }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(de.ledgerline.app.R.string.pw_show_favorites)) },
                                    leadingIcon = { Icon(if (favOnly) Icons.Outlined.Star else Icons.Outlined.StarBorder, null) },
                                    onClick = { vm.toggleFavoritesOnly(); overflow = false },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (ui.trashCount > 0) stringResource(de.ledgerline.app.R.string.trash_open, ui.trashCount) else stringResource(de.ledgerline.app.R.string.pw_tab_all)) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { vm.setShowTrash(!showTrash); overflow = false },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(de.ledgerline.app.R.string.history_title)) },
                                    leadingIcon = { Icon(Icons.Outlined.History, null) },
                                    onClick = { showHistory = true; overflow = false },
                                )
                                if (folders.isNotEmpty()) {
                                    androidx.compose.material3.HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(de.ledgerline.app.R.string.pw_folder_none)) },
                                        leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                                        trailingIcon = { if (folderFilter == null) Icon(Icons.Filled.Check, null, tint = Brand.accent) },
                                        onClick = { vm.setFolderFilter(null); overflow = false },
                                    )
                                    folders.forEach { f ->
                                        DropdownMenuItem(
                                            text = { Text(f.name) },
                                            leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                                            trailingIcon = { if (folderFilter == f.id) Icon(Icons.Filled.Check, null, tint = Brand.accent) },
                                            onClick = { vm.setFolderFilter(f.id); overflow = false },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
                if (searchActive) {
                    de.ledgerline.app.ui.workspace.common.SearchField(query = query, onQueryChange = vm::setQuery)
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::reload,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = de.ledgerline.app.ui.common.ListBottomPadding) {
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
                        listSection(ui.secrets, key = { it.id }) { s ->
                            de.ledgerline.app.ui.common.LedgerRow(
                                title = s.title.ifBlank { typeLabel(s.type) },
                                subtitle = SecretFields.subtitle(s).takeIf { it.isNotBlank() },
                                leading = { SecretAvatar(s, vm) },
                                trailing = {
                                    if (s.favorite) Icon(Icons.Outlined.Star, contentDescription = null, tint = Brand.accent, modifier = Modifier.size(18.dp))
                                    else de.ledgerline.app.ui.common.RowChevron()
                                },
                                onClick = { onOpen(s.id) },
                            )
                        }
                    }
                }
            }
            val vaultTabs = listOf(
                stringResource(de.ledgerline.app.R.string.pw_tab_all),
                stringResource(de.ledgerline.app.R.string.pw_tab_logins),
                stringResource(de.ledgerline.app.R.string.pw_tab_cards),
                stringResource(de.ledgerline.app.R.string.pw_tab_passkeys),
            )
            val vaultTabIndex = when (typeFilter) { "login" -> 1; "card" -> 2; "passkey" -> 3; else -> 0 }
            de.ledgerline.app.ui.workspace.common.FloatingTabBar(
                tabs = vaultTabs,
                selectedIndex = vaultTabIndex,
                onSelect = { vm.setTypeFilter(when (it) { 1 -> "login"; 2 -> "card"; 3 -> "passkey"; else -> null }) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            if (!showTrash) {
                FloatingActionButton(onClick = { picker = true }, modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).padding(bottom = 60.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(de.ledgerline.app.R.string.cd_add))
                }
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
    var showMoveDialog by remember { mutableStateOf(false) }
    if (showMoveDialog) {
        de.ledgerline.app.ui.common.TextInputDialog(
            title = stringResource(de.ledgerline.app.R.string.pw_move_vault),
            label = stringResource(de.ledgerline.app.R.string.vaults_name),
            initial = item.title,
            confirmLabel = stringResource(de.ledgerline.app.R.string.pw_move_vault),
            onConfirm = { name -> showMoveDialog = false; vm.moveToSharedVault(item.id, name.ifBlank { item.title }); onBack() },
            onDismiss = { showMoveDialog = false },
        )
    }
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
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(de.ledgerline.app.R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(de.ledgerline.app.R.string.pw_move_vault)) },
                            leadingIcon = { Icon(Icons.Outlined.Share, null) },
                            onClick = { menu = false; showMoveDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(de.ledgerline.app.R.string.cd_trash)) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            onClick = { menu = false; vm.trash(item.id); onBack() },
                        )
                    }
                }
            })
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PwHeaderCard(item, vm)
            SecurityCard(item, vm, context)

            // Scalar type fields (URLs + TOTP handled in their own cards) → one grouped card.
            val scalar = (SecretTypes.fields[item.type] ?: emptyList())
                .filter { it != "urls" && it != "totp" && SecretFields.str(item, it).isNotBlank() }
            if (scalar.isNotEmpty()) {
                DetailCard(de.ledgerline.app.R.string.pw_details) {
                    scalar.forEachIndexed { i, key ->
                        if (i > 0) RowDivider()
                        ValueRow(fieldLabel(key), SecretFields.str(item, key), secret = SecretTypes.isSecretKey(key), context = context)
                    }
                }
            }

            val urls = SecretFields.urls(item)
            if (urls.isNotEmpty()) {
                DetailCard(de.ledgerline.app.R.string.pw_website) {
                    urls.forEachIndexed { i, u ->
                        if (i > 0) RowDivider()
                        ValueRow(fieldLabel("urls"), u, secret = false, isUrl = true, context = context)
                    }
                }
            }

            SecretFields.str(item, "totp").takeIf { it.isNotBlank() }?.let { TotpCard(it, context) }

            if (item.custom.isNotEmpty()) {
                DetailCard(de.ledgerline.app.R.string.pw_custom_fields) {
                    item.custom.forEachIndexed { i, c ->
                        if (i > 0) RowDivider()
                        ValueRow(c.label.ifBlank { "Field" }, c.value, secret = c.kind == "secret", isUrl = c.kind == "url", multiline = c.kind == "multiline", context = context)
                    }
                }
            }

            if (item.tags.isNotEmpty()) TagsCard(item.tags)
            PasskeysSection(item, vm)
            if (item.versions.isNotEmpty()) VersionHistory(item, vm)
        }
    }
}

/** Identity header: favicon/type avatar + title + type name + favourite marker, in a card. */
@Composable
private fun PwHeaderCard(item: SecretItem, vm: PasswordsViewModel) {
    Row(Modifier.fillMaxWidth().cardSurface(), verticalAlignment = Alignment.CenterVertically) {
        SecretAvatar(item, vm, size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title.ifBlank { typeLabel(item.type) },
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                maxLines = 2,
            )
            Text(typeLabel(item.type), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.favorite) Icon(Icons.Outlined.Star, contentDescription = null, tint = Brand.tintOrange)
    }
}

/** A titled grouped card holding [content] value rows separated by [RowDivider]s. */
@Composable
private fun DetailCard(headerRes: Int, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().cardSurface(padded = false)) {
        Text(
            stringResource(headerRes),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = Brand.accent,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
        )
        content()
        Spacer(Modifier.height(6.dp))
    }
}

/** A hairline divider between rows, indented to the value column. */
@Composable
private fun RowDivider() {
    androidx.compose.material3.HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/**
 * The 1Password-style security summary for a login/password: strength meter, breach check, and the
 * "site offers 2FA" hint. Renders nothing when there's no password and no 2FA offer.
 */
@Composable
private fun SecurityCard(item: SecretItem, vm: PasswordsViewModel, context: android.content.Context) {
    val password = SecretFields.str(item, "password")
    val tfaUrl = vm.tfaSetupUrl(item)
    if (password.isBlank() && tfaUrl == null) return
    Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(de.ledgerline.app.R.string.pw_security), style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = Brand.accent)
        if (password.isNotBlank()) {
            StrengthBar(password)
            BreachRow(item, vm)
        }
        tfaUrl?.let { TfaOfferRow(it, context) }
    }
}

/** URLs and other tappable-link cards reuse [ValueRow] with isUrl=true. */
@Composable
private fun TagsCard(tags: List<String>) {
    Column(Modifier.fillMaxWidth().cardSurface()) {
        Text(stringResource(de.ledgerline.app.R.string.pw_tags), style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = Brand.accent, modifier = Modifier.padding(bottom = 8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { t -> AssistChip(onClick = {}, label = { Text(t) }) }
        }
    }
}

/** TOTP card: the live code, a countdown ring, and a copy button. */
@Composable
private fun TotpCard(secret: String, context: android.content.Context) {
    Column(Modifier.fillMaxWidth().cardSurface()) {
        Text(stringResource(de.ledgerline.app.R.string.pw_totp), style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = Brand.accent, modifier = Modifier.padding(bottom = 6.dp))
        TotpRow(secret, context)
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
    Column(Modifier.fillMaxWidth().cardSurface()) {
        Text(
            stringResource(de.ledgerline.app.R.string.pw_passkeys),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = Brand.accent,
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
    Column(Modifier.fillMaxWidth()) {
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
    Column(Modifier.fillMaxWidth().cardSurface()) {
        Text(stringResource(de.ledgerline.app.R.string.pw_version_history), style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = Brand.accent)
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

/**
 * A labelled value row (1Password-style): a caption label above the value; secrets are masked with
 * a reveal toggle + a "show large" button (opens a char-colour-coded full-screen reading view);
 * URLs open in the browser; every row has a copy button. Copies route through [SecureClipboard]
 * (sensitive + auto-clear for secrets).
 */
@Composable
private fun ValueRow(
    label: String,
    value: String,
    secret: Boolean,
    context: android.content.Context,
    isUrl: Boolean = false,
    multiline: Boolean = false,
) {
    var revealed by remember { mutableStateOf(!secret) }
    var large by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (secret && !revealed) "•".repeat(minOf(maxOf(value.length, 8), 20)) else value,
                modifier = Modifier.weight(1f),
                maxLines = if (multiline || revealed) Int.MAX_VALUE else 1,
                color = if (isUrl) Brand.accent else androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                style = if (secret) androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) else androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            )
            if (isUrl) {
                IconButton(onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(value.ensureScheme()))) }
                }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) }
            }
            if (secret) {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = stringResource(de.ledgerline.app.R.string.cd_reveal))
                }
                IconButton(onClick = { large = true }) {
                    Icon(Icons.Outlined.ZoomIn, contentDescription = stringResource(de.ledgerline.app.R.string.cd_large_view))
                }
            }
            IconButton(onClick = { if (secret) SecureClipboard.copySensitive(context, label, value) else SecureClipboard.copyPlain(context, label, value) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(de.ledgerline.app.R.string.cd_copy))
            }
        }
    }
    if (large) LargeSecretDialog(label, value, context) { large = false }
}

/** Prefix a bare host with https:// so it opens as a URL. */
private fun String.ensureScheme(): String = if (contains("://")) this else "https://$this"

/**
 * Full-screen, easy-to-read rendering of a secret: large monospace with each character colour-coded
 * (digits = accent, letters = normal, symbols = orange) so it's unambiguous to read aloud. Read-only.
 */
@Composable
private fun LargeSecretDialog(label: String, value: String, context: android.content.Context, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(Brand.cardRadius))
                .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                text = colorizeSecret(value),
                style = androidx.compose.material3.MaterialTheme.typography.headlineMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(de.ledgerline.app.R.string.action_close)) }
                androidx.compose.material3.FilledTonalButton(onClick = { SecureClipboard.copySensitive(context, label, value) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(de.ledgerline.app.R.string.cd_copy))
                }
            }
        }
    }
}

/** Colour each character of a secret: digits accent, symbols orange, letters default. */
@Composable
private fun colorizeSecret(value: String): androidx.compose.ui.text.AnnotatedString {
    val accent = Brand.accent
    val symbol = Brand.tintOrange
    val letter = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    return androidx.compose.ui.text.buildAnnotatedString {
        value.forEach { ch ->
            val c = when {
                ch.isDigit() -> accent
                ch.isLetter() -> letter
                else -> symbol
            }
            withStyle(androidx.compose.ui.text.SpanStyle(color = c)) { append(ch.toString()) }
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        val display = code?.let { it.substring(0, 3) + " " + it.substring(3) } ?: "——— ———"
        Text(
            display,
            modifier = Modifier.weight(1f),
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            color = Brand.accent,
        )
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(
                progress = { remaining / 30f },
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
                color = if (remaining <= 5) androidx.compose.material3.MaterialTheme.colorScheme.error else Brand.accent,
                trackColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
            )
            Text("$remaining", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { code?.let { SecureClipboard.copySensitive(context, "TOTP", it) } }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(de.ledgerline.app.R.string.cd_copy_code))
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
