package de.ledgerline.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.runtime.mutableStateListOf
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
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.admin.ADMIN_MODULES
import de.ledgerline.app.domain.model.admin.AdminGroup
import de.ledgerline.app.domain.model.admin.AuditEntry
import de.ledgerline.app.domain.model.admin.BackupJob
import de.ledgerline.app.domain.model.admin.NotificationsSettings
import de.ledgerline.app.domain.model.admin.SystemOverview
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.LedgerRow
import de.ledgerline.app.ui.common.ListBottomPadding
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.common.listSection
import de.ledgerline.app.ui.theme.cardSurface
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------
//  Notifications (SMTP / ntfy / webhook) + test
// ---------------------------------------------------------------------------
@Composable
internal fun NotificationsAdminScreen(vm: AdminViewModel, onBack: () -> Unit) {
    var s by remember { mutableStateOf<NotificationsSettings?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { s = vm.notifications() }
    val cur = s
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.admin_notifications), onBack = onBack) }) { pad ->
        if (cur == null) { Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }; return@AppScaffold }
        var mailEnabled by remember(cur) { mutableStateOf(cur.mailEnabled) }
        var host by remember(cur) { mutableStateOf(cur.smtpHost ?: "") }
        var port by remember(cur) { mutableStateOf(cur.smtpPort?.toString() ?: "") }
        var enc by remember(cur) { mutableStateOf(cur.smtpEncryption ?: "tls") }
        var user by remember(cur) { mutableStateOf(cur.smtpUsername ?: "") }
        var pass by remember(cur) { mutableStateOf("") }
        var fromAddr by remember(cur) { mutableStateOf(cur.smtpFromAddress ?: "") }
        var fromName by remember(cur) { mutableStateOf(cur.smtpFromName ?: "") }
        var ntfyEnabled by remember(cur) { mutableStateOf(cur.ntfyEnabled) }
        var ntfyUrl by remember(cur) { mutableStateOf(cur.ntfyUrl ?: "") }
        var ntfyTopic by remember(cur) { mutableStateOf(cur.ntfyTopic ?: "") }
        var ntfyToken by remember(cur) { mutableStateOf("") }
        var whEnabled by remember(cur) { mutableStateOf(cur.webhookEnabled) }
        var whUrl by remember(cur) { mutableStateOf(cur.webhookUrl ?: "") }
        var whSecret by remember(cur) { mutableStateOf("") }

        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            // Email
            ToggleRow(stringResource(R.string.admin_mail), mailEnabled) { mailEnabled = it }
            F(host, { host = it }, "SMTP host"); NumF(port, { port = it }, "Port")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(enc == "tls", { enc = "tls" }, { Text("TLS") }); FilterChip(enc == "ssl", { enc = "ssl" }, { Text("SSL") })
            }
            F(user, { user = it }, "Username"); Secret(pass, { pass = it }, cur.hasSmtpPassword)
            F(fromAddr, { fromAddr = it }, "From address"); F(fromName, { fromName = it }, "From name")
            AssistChip(onClick = { vm.testNotification("mail") { msg = if (it) "OK" else null } }, label = { Text(stringResource(R.string.admin_test)) })
            // ntfy
            SectionLabel(stringResource(R.string.admin_ntfy))
            ToggleRow(stringResource(R.string.admin_enabled), ntfyEnabled) { ntfyEnabled = it }
            F(ntfyUrl, { ntfyUrl = it }, "ntfy URL"); F(ntfyTopic, { ntfyTopic = it }, "Topic"); Secret(ntfyToken, { ntfyToken = it }, cur.hasNtfyToken)
            AssistChip(onClick = { vm.testNotification("ntfy") { msg = if (it) "OK" else null } }, label = { Text(stringResource(R.string.admin_test)) })
            // webhook
            SectionLabel(stringResource(R.string.admin_webhook))
            ToggleRow(stringResource(R.string.admin_enabled), whEnabled) { whEnabled = it }
            F(whUrl, { whUrl = it }, "Webhook URL"); Secret(whSecret, { whSecret = it }, cur.hasWebhookSecret)
            AssistChip(onClick = { vm.testNotification("webhook") { msg = if (it) "OK" else null } }, label = { Text(stringResource(R.string.admin_test)) })

            de.ledgerline.app.ui.theme.PrimaryGradientButton(stringResource(R.string.action_save), onClick = {
                val body = buildJsonObject {
                    put("mail_enabled", mailEnabled)
                    put("smtp_host", host.trim()); port.toIntOrNull()?.let { put("smtp_port", it) }
                    put("smtp_encryption", enc); put("smtp_username", user.trim())
                    if (pass.isNotBlank()) put("smtp_password", pass)
                    put("smtp_from_address", fromAddr.trim()); put("smtp_from_name", fromName.trim())
                    put("ntfy_enabled", ntfyEnabled); put("ntfy_url", ntfyUrl.trim()); put("ntfy_topic", ntfyTopic.trim())
                    if (ntfyToken.isNotBlank()) put("ntfy_token", ntfyToken)
                    put("webhook_enabled", whEnabled); put("webhook_url", whUrl.trim())
                    if (whSecret.isNotBlank()) put("webhook_secret", whSecret)
                }
                vm.updateNotifications(body) { msg = if (it) "OK" else null }
            })
        }
    }
}

// ---------------------------------------------------------------------------
//  System status
// ---------------------------------------------------------------------------
@Composable
internal fun SystemScreen(vm: AdminViewModel, onBack: () -> Unit) {
    var sys by remember { mutableStateOf<SystemOverview?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { sys = vm.system() }
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.admin_system), onBack = onBack) }) { pad ->
        val o = sys
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (o == null) { Text(stringResource(R.string.admin_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant); return@Column }
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Kv(stringResource(R.string.admin_sys_version), o.status.version)
                Kv(stringResource(R.string.admin_sys_queue), "${o.status.queue.pending} / ${o.status.queue.failed}")
                Kv(stringResource(R.string.admin_sys_storage), fmtBytes(o.status.storage.total))
                Kv(stringResource(R.string.admin_sys_disk), fmtBytes(o.status.disk.free) + " / " + fmtBytes(o.status.disk.total))
                Kv(stringResource(R.string.admin_sys_errors), "${o.status.errors.unresolved} / ${o.status.errors.total}")
            }
            if (o.errors.isNotEmpty()) {
                SectionLabel(stringResource(R.string.admin_sys_errors))
                o.errors.forEach { e ->
                    Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(e.exception.substringAfterLast('\\'), style = MaterialTheme.typography.bodyMedium)
                        Text(e.message.take(120), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("×${e.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (e.resolvedAt == null) TextButton(onClick = { vm.resolveError(e.id) { reload++ } }) { Text(stringResource(R.string.admin_resolve)) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Groups
// ---------------------------------------------------------------------------
@Composable
internal fun GroupsScreen(vm: AdminViewModel, onBack: () -> Unit) {
    var groups by remember { mutableStateOf<List<AdminGroup>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<AdminGroup?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { groups = vm.groups() }
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.admin_groups), onBack = onBack, actions = { TextButton(onClick = { creating = true }) { Text(stringResource(R.string.action_add)) } }) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                listSection(groups, key = { "g${it.id}" }) { g ->
                    LedgerRow(title = g.name, subtitle = "${g.members.size} · ${g.modules?.joinToString(",") ?: "all"}", onClick = { editing = g })
                }
            }
        }
    }
    if (creating) GroupEditDialog(null, { editing = null; creating = false; vm.saveGroup(null, it) { reload++ } }, null) { creating = false }
    editing?.let { g -> GroupEditDialog(g, { editing = null; vm.saveGroup(g.id, it) { reload++ } }, { editing = null; vm.deleteGroup(g.id) { reload++ } }) { editing = null } }
}

@Composable
private fun GroupEditDialog(initial: AdminGroup?, onSave: (kotlinx.serialization.json.JsonObject) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var cap by remember { mutableStateOf(initial?.maxConnectedDevices?.toString() ?: "") }
    var shareable by remember { mutableStateOf(initial?.shareable ?: false) }
    val modules = remember { mutableStateListOf<String>().apply { initial?.modules?.let { addAll(it) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.action_add else R.string.action_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.admin_group_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cap, { cap = it }, label = { Text(stringResource(R.string.admin_max_devices)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.admin_shareable), Modifier.weight(1f)); Switch(shareable, { shareable = it }) }
                SectionLabel(stringResource(R.string.admin_modules))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ADMIN_MODULES.forEach { m -> FilterChip(m in modules, { if (m in modules) modules.remove(m) else modules.add(m) }, { Text(m) }) }
                }
                onDelete?.let { TextButton(onClick = it) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(buildJsonObject {
                    put("name", name.trim())
                    put("max_connected_devices", cap.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("shareable", shareable)
                    put("modules", if (modules.isEmpty()) JsonNull else JsonArray(modules.map { JsonPrimitive(it) }))
                })
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ---------------------------------------------------------------------------
//  Security log
// ---------------------------------------------------------------------------
@Composable
internal fun SecurityLogScreen(vm: AdminViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(query) { entries = vm.securityLog(query, null, null, 1, 100)?.data.orEmpty() }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val bytes = vm.securityLogExport("csv")
            if (bytes != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
        }
    }
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.admin_security_log), onBack = onBack, actions = { TextButton(onClick = { exporter.launch("security-log.csv") }) { Text(stringResource(R.string.admin_export)) } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(query, { query = it }, label = { Text("action") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                listSection(entries, key = { "a${it.at}-${it.action}-${it.userId}" }) { e ->
                    LedgerRow(
                        title = e.action,
                        subtitle = listOfNotNull(e.actor, e.ip, e.at?.take(19)?.replace('T', ' ')).joinToString(" · ").ifBlank { null },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Backup — destinations / jobs / runs (operate; credential authoring stays on web)
// ---------------------------------------------------------------------------
@Composable
internal fun BackupScreen(vm: AdminViewModel, onBack: () -> Unit) {
    var jobs by remember { mutableStateOf<List<BackupJob>>(emptyList()) }
    var runs by remember { mutableStateOf<List<de.ledgerline.app.domain.model.admin.BackupRun>>(emptyList()) }
    var dests by remember { mutableStateOf<List<de.ledgerline.app.domain.model.admin.BackupDestination>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { jobs = vm.backupJobs(); runs = vm.backupRuns(); dests = vm.backupDestinations() }
    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.admin_backup), onBack = onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionLabel(stringResource(R.string.admin_backup_destinations))
            if (dests.isEmpty()) EmptyHint() else dests.forEach { d ->
                LedgerRow(title = d.name, subtitle = d.driver)
            }
            SectionLabel(stringResource(R.string.admin_backup_jobs))
            if (jobs.isEmpty()) EmptyHint() else jobs.forEach { j ->
                LedgerRow(
                    title = j.name,
                    subtitle = "${j.sources.joinToString(",")} · ${j.cron} · ${j.lastStatus ?: "—"}",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { vm.runBackupJob(j.id) { reload++ } }) { Text(stringResource(R.string.admin_run_now)) }
                            IconButton(onClick = { vm.deleteBackupJob(j.id) { reload++ } }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete)) }
                        }
                    },
                )
            }
            SectionLabel(stringResource(R.string.admin_backup_runs))
            if (runs.isEmpty()) EmptyHint() else runs.forEach { r ->
                LedgerRow(
                    title = (r.job ?: "#${r.id}") + " · " + r.status,
                    subtitle = listOfNotNull(r.startedHuman, r.size).joinToString(" · ").ifBlank { null },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (r.verifyStatus == null && r.archives.isNotEmpty()) TextButton(onClick = { vm.verifyBackupRun(r.id, r.archives.first().source, null) { reload++ } }) { Text(stringResource(R.string.admin_verify)) }
                            if (r.cancellable) TextButton(onClick = { vm.cancelBackupRun(r.id) { reload++ } }) { Text(stringResource(R.string.admin_cancel_run), color = MaterialTheme.colorScheme.error) }
                        }
                    },
                )
            }
        }
    }
}

// ---- small shared bits ----
@Composable private fun EmptyHint() = Text(stringResource(R.string.admin_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
@Composable private fun Kv(k: String, v: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, style = MaterialTheme.typography.bodyMedium) }
@Composable private fun ToggleRow(label: String, v: Boolean, on: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(v, on) }
@Composable private fun F(v: String, on: (String) -> Unit, label: String) = OutlinedTextField(v, on, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
@Composable private fun NumF(v: String, on: (String) -> Unit, label: String) = OutlinedTextField(v, on, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
@Composable private fun Secret(v: String, on: (String) -> Unit, has: Boolean) = OutlinedTextField(v, on, label = { Text(stringResource(if (has) R.string.admin_secret_keep else R.string.share_password_optional)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())

private fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b B"; val u = listOf("KB","MB","GB","TB"); var v = b.toDouble()/1024; var i = 0
    while (v >= 1024 && i < u.size-1) { v /= 1024; i++ }; return String.format(java.util.Locale.US, "%.1f %s", v, u[i])
}
