package de.ledgerline.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.admin.ADMIN_MODULES
import de.ledgerline.app.domain.model.admin.AdminUser
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.LedgerRow
import de.ledgerline.app.ui.common.ListBottomPadding
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.common.SoftIconChip
import de.ledgerline.app.ui.common.listSection
import de.ledgerline.app.ui.files.copyToClipboard
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.cardSurface
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class AdminSub { HUB, USERS, ACCESS, NOTIFICATIONS, SYSTEM, GROUPS, SECURITY_LOG, BACKUP }

/** Admin section, shown only to admins (`/me.user.groups` contains "admin"). Internal sub-nav. */
@Composable
fun AdminScreen(onBack: () -> Unit, vm: AdminViewModel = hiltViewModel()) {
    var sub by remember { mutableStateOf(AdminSub.HUB) }
    val back = { sub = AdminSub.HUB }
    when (sub) {
        AdminSub.USERS -> UsersScreen(vm, back)
        AdminSub.ACCESS -> AccessScreen(vm, back)
        AdminSub.NOTIFICATIONS -> NotificationsAdminScreen(vm, back)
        AdminSub.SYSTEM -> SystemScreen(vm, back)
        AdminSub.GROUPS -> GroupsScreen(vm, back)
        AdminSub.SECURITY_LOG -> SecurityLogScreen(vm, back)
        AdminSub.BACKUP -> BackupScreen(vm, back)
        AdminSub.HUB -> AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.admin_title), onBack = onBack) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                de.ledgerline.app.ui.common.ListSectionCard {
                    HubRow(stringResource(R.string.admin_users), Icons.Outlined.Group, Brand.tintBlue) { sub = AdminSub.USERS }
                    de.ledgerline.app.ui.common.RowDivider()
                    HubRow(stringResource(R.string.admin_groups), Icons.Outlined.Group, Brand.tintTeal) { sub = AdminSub.GROUPS }
                    de.ledgerline.app.ui.common.RowDivider()
                    HubRow(stringResource(R.string.admin_access), Icons.Outlined.Tune, Brand.tintGreen) { sub = AdminSub.ACCESS }
                    de.ledgerline.app.ui.common.RowDivider()
                    HubRow(stringResource(R.string.admin_notifications), Icons.Outlined.Notifications, Brand.tintOrange) { sub = AdminSub.NOTIFICATIONS }
                    de.ledgerline.app.ui.common.RowDivider()
                    HubRow(stringResource(R.string.admin_system), Icons.Outlined.Monitor, Brand.tintViolet) { sub = AdminSub.SYSTEM }
                    de.ledgerline.app.ui.common.RowDivider()
                    HubRow(stringResource(R.string.admin_security_log), Icons.Outlined.Lock, Brand.tintGray) { sub = AdminSub.SECURITY_LOG }
                    de.ledgerline.app.ui.common.RowDivider()
                    HubRow(stringResource(R.string.admin_backup), Icons.Outlined.Backup, Brand.tintBlue) { sub = AdminSub.BACKUP }
                }
            }
        }
    }
}

@Composable
private fun HubRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    LedgerRow(title = label, leading = { SoftIconChip(icon, tint = tint) }, trailing = { de.ledgerline.app.ui.common.RowChevron() }, onClick = onClick)
}

// ---------------------------------------------------------------------------
//  Users
// ---------------------------------------------------------------------------
@Composable
private fun UsersScreen(vm: AdminViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var users by remember { mutableStateOf<List<AdminUser>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<AdminUser?>(null) }
    var creating by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) { users = vm.users() }

    AppScaffold(topBar = {
        AppTopBar(title = stringResource(R.string.admin_users), onBack = onBack, actions = {
            TextButton(onClick = { creating = true }) { Text(stringResource(R.string.action_add)) }
        })
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                listSection(users, key = { "u${it.id}" }) { u ->
                    LedgerRow(
                        title = u.name,
                        subtitle = "${u.email} · ${if (u.role == "admin") stringResource(R.string.admin_role_admin) else stringResource(R.string.admin_role_user)}",
                        leading = { SoftIconChip(Icons.Outlined.Person, tint = if (u.role == "admin") Brand.accent else Brand.tintGray) },
                        trailing = { de.ledgerline.app.ui.common.RowChevron() },
                        onClick = { editing = u },
                    )
                }
            }
        }
    }

    if (creating) UserEditDialog(null, onSave = { body -> creating = false; vm.saveUser(null, body) { reload++ } }, onDismiss = { creating = false })
    editing?.let { u ->
        UserEditDialog(
            u,
            onSave = { body -> editing = null; vm.saveUser(u.id, body) { reload++ } },
            onDelete = { editing = null; vm.deleteUser(u.id) { reload++ } },
            onResetPw = { vm.resetPassword(u.id) {} },
            onReset2fa = { vm.resetTwoFactor(u.id) {} },
            onInvite = { vm.inviteLink(u.id, 168, false) { r -> invite = r?.url } },
            onDismiss = { editing = null },
        )
    }
    invite?.let { url ->
        AlertDialog(
            onDismissRequest = { invite = null },
            title = { Text(stringResource(R.string.admin_invite_created)) },
            text = { SelectionContainer { Text(url, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().cardSurface()) } },
            confirmButton = { TextButton(onClick = { copyToClipboard(ctx, url); invite = null }) { Text(stringResource(R.string.share_copy_link)) } },
            dismissButton = { TextButton(onClick = { invite = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun UserEditDialog(
    initial: AdminUser?,
    onSave: (kotlinx.serialization.json.JsonObject) -> Unit,
    onDelete: (() -> Unit)? = null,
    onResetPw: (() -> Unit)? = null,
    onReset2fa: (() -> Unit)? = null,
    onInvite: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var admin by remember { mutableStateOf(initial?.role == "admin") }
    var password by remember { mutableStateOf("") }
    var maxDevices by remember { mutableStateOf(initial?.maxConnectedDevices?.toString() ?: "") }
    val modules = remember { mutableStateListOfInit(initial?.modules) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.admin_user_new else R.string.action_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.contact_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.login_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (initial == null) OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.admin_password_optional)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.admin_role_admin), Modifier.weight(1f))
                    Switch(checked = admin, onCheckedChange = { admin = it })
                }
                OutlinedTextField(maxDevices, { maxDevices = it }, label = { Text(stringResource(R.string.admin_max_devices)) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                SectionLabel(stringResource(R.string.admin_modules))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ADMIN_MODULES.forEach { m ->
                        FilterChip(selected = m in modules, onClick = { if (m in modules) modules.remove(m) else modules.add(m) }, label = { Text(m) })
                    }
                }
                if (initial != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onResetPw?.let { AssistChip(onClick = it, label = { Text(stringResource(R.string.admin_reset_password)) }) }
                        onReset2fa?.let { AssistChip(onClick = it, label = { Text(stringResource(R.string.admin_reset_2fa)) }) }
                    }
                    onInvite?.let { AssistChip(onClick = it, label = { Text(stringResource(R.string.admin_invite_link)) }) }
                    onDelete?.let { TextButton(onClick = it) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && email.isNotBlank(), onClick = {
                onSave(buildJsonObject {
                    put("name", name.trim()); put("email", email.trim()); put("role", if (admin) "admin" else "user")
                    if (initial == null && password.isNotBlank()) put("password", password)
                    put("max_connected_devices", maxDevices.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
                    // Empty selection = all modules → send null; else the allow-list.
                    put("modules", if (modules.isEmpty()) JsonNull else JsonArray(modules.map { JsonPrimitive(it) }))
                })
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun mutableStateListOfInit(init: List<String>?) =
    androidx.compose.runtime.mutableStateListOf<String>().apply { init?.let { addAll(it) } }

// ---------------------------------------------------------------------------
//  Access & limits
// ---------------------------------------------------------------------------
@Composable
private fun AccessScreen(vm: AdminViewModel, onBack: () -> Unit) {
    var registration by remember { mutableStateOf<Boolean?>(null) }
    var deviceCap by remember { mutableStateOf("") }
    var maxUpload by remember { mutableStateOf("") }
    var orphanGrace by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        registration = vm.registration()
        vm.devicePolicy()?.let { deviceCap = it.maxConnectedDevices.toString() }
        vm.filesLimits()?.let { maxUpload = it.filesMaxUploadMb.toString(); orphanGrace = it.filesBlobOrphanGraceHours.toString() }
    }
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.admin_access), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Row(Modifier.fillMaxWidth().cardSurface(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.admin_registration), Modifier.weight(1f))
                Switch(checked = registration == true, onCheckedChange = { v -> registration = v; vm.setRegistration(v) { registration = it } })
            }
            SectionLabel(stringResource(R.string.admin_device_cap))
            NumberSave(deviceCap, { deviceCap = it }, R.string.admin_max_devices) { deviceCap.toIntOrNull()?.let { n -> vm.setDevicePolicy(n) { msg = it?.let { r -> "OK" } } } }
            SectionLabel(stringResource(R.string.admin_files_max_upload))
            NumberSave(maxUpload, { maxUpload = it }, R.string.admin_files_max_upload) {
                val mb = maxUpload.toIntOrNull(); val gr = orphanGrace.toIntOrNull()
                if (mb != null && gr != null) vm.setFilesLimits(mb, gr) { msg = "OK" }
            }
            NumberSave(orphanGrace, { orphanGrace = it }, R.string.admin_files_orphan_grace) {
                val mb = maxUpload.toIntOrNull(); val gr = orphanGrace.toIntOrNull()
                if (mb != null && gr != null) vm.setFilesLimits(mb, gr) { msg = "OK" }
            }
        }
    }
}

@Composable
private fun NumberSave(value: String, onChange: (String) -> Unit, labelRes: Int, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value, onChange, label = { Text(stringResource(labelRes)) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
    }
}
