package de.ledgerline.app.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.core.finance.Eigenbeleg
import de.ledgerline.app.domain.model.Transaction
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.theme.cardSurface

/** Create a self-issued voucher (Eigenbeleg) for a booking: prefilled paper-form + signature pad. */
@Composable
fun EigenbelegScreen(tx: Transaction, vm: FinanceViewModel, onBack: () -> Unit) {
    val initial = remember(tx.id) { vm.eigenbelegDraft(tx) }
    var grund by remember { mutableStateOf(initial.grund) }
    var grundOther by remember { mutableStateOf(initial.grundOther) }
    var recipient by remember { mutableStateOf(initial.recipient) }
    var address by remember { mutableStateOf(initial.address) }
    var ort by remember { mutableStateOf(initial.ort) }
    var date by remember { mutableStateOf(initial.date) }
    var buchungstext by remember { mutableStateOf(initial.buchungstext) }
    var gross by remember { mutableStateOf(if (initial.gross == 0.0) "" else initial.gross.toString()) }
    var vatRate by remember { mutableStateOf(egTrim(initial.vatRate)) }
    var reason by remember { mutableStateOf(initial.reason) }
    var issuer by remember { mutableStateOf(initial.issuer) }
    var signatureUri by remember { mutableStateOf("") }
    var reseedKey by remember { mutableStateOf(0) }
    var bigOpen by remember { mutableStateOf(false) }
    val sigController = remember { de.ledgerline.app.ui.common.SignatureController() }
    var busy by remember { mutableStateOf(false) }

    fun draft() = Eigenbeleg.Draft(
        grund = grund, grundOther = grundOther.trim(), recipient = recipient.trim(), address = address.trim(),
        ort = ort.trim(), date = date.trim(), createdAt = initial.createdAt,
        buchungstext = buchungstext.trim(), gross = gross.replace(',', '.').toDoubleOrNull() ?: 0.0,
        vatRate = vatRate.replace(',', '.').toDoubleOrNull() ?: 0.0, reason = reason.trim(), issuer = issuer.trim(),
        signature = signatureUri.ifBlank { sigController.dataUri() },
    )
    val valid = draft().valid

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.finance_eg_title),
                onBack = onBack,
                actions = {
                    TextButton(enabled = valid && !busy, onClick = {
                        busy = true
                        vm.createEigenbeleg(tx, draft()) { ok -> busy = false; if (ok) onBack() }
                    }) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.finance_eg_reason_label))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eigenbeleg.GRUND_OPTIONS.forEach { g ->
                        FilterChip(selected = grund == g, onClick = { grund = g }, label = { Text(grundLabel(g)) })
                    }
                }
                if (grund == "sonstiges") OutlinedTextField(grundOther, { grundOther = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_eg_grund_other)) }, singleLine = true)
            }

            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.finance_eg_details))
                OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_date)) }, singleLine = true)
                OutlinedTextField(recipient, { recipient = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_eg_recipient)) }, singleLine = true)
                OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_customer_address)) })
                OutlinedTextField(buchungstext, { buchungstext = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_eg_buchungstext)) })
            }

            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.finance_eg_amount))
                OutlinedTextField(gross, { gross = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_eg_gross)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(vatRate, { vatRate = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_eg_vatrate)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                val d = draft()
                Text(stringResource(R.string.finance_eg_net_vat, vm.money(d.net, null), vm.money(d.vat, null)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (grund == "betriebsausgabe") {
                Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(R.string.finance_eg_missing))
                    OutlinedTextField(reason, { reason = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_eg_reason)) })
                }
            }

            Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.finance_eg_issuer_sig))
                OutlinedTextField(issuer, { issuer = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_eg_issuer)) }, singleLine = true)
                OutlinedTextField(ort, { ort = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.finance_eg_place)) }, singleLine = true)
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.finance_eg_signature), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { bigOpen = true }) { Text(stringResource(R.string.finance_eg_sig_big)) }
                    IconButton(onClick = { sigController.clear(); signatureUri = "" }) { Icon(Icons.Outlined.Clear, stringResource(R.string.finance_eg_sig_clear)) }
                }
                // Native, fast signature pad (taller); re-seeded when the fullscreen pad returns.
                androidx.compose.runtime.key(reseedKey) {
                    de.ledgerline.app.ui.common.SignaturePad(
                        controller = sigController,
                        modifier = Modifier.fillMaxWidth().height(200.dp).cardSurface(padded = false),
                        initialUri = signatureUri,
                        onChanged = { signatureUri = it },
                    )
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }

    // Fullscreen LANDSCAPE signing surface (iOS parity) — the pad fills the whole screen sideways.
    if (bigOpen) {
        val bigController = remember { de.ledgerline.app.ui.common.SignatureController() }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // Lock the activity to landscape while the sheet is open; restore on close.
        androidx.compose.runtime.DisposableEffect(Unit) {
            val act = de.ledgerline.app.ui.common.findActivity(ctx)
            val prev = act?.requestedOrientation
            act?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            onDispose { act?.requestedOrientation = prev ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { bigOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.finance_eg_signature), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { bigController.clear() }) { Text(stringResource(R.string.finance_eg_sig_clear)) }
                        TextButton(onClick = { bigOpen = false }) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(onClick = { signatureUri = bigController.dataUri(); reseedKey++; bigOpen = false }) { Text(stringResource(R.string.action_save)) }
                    }
                    // Full-bleed pad with a dotted signing baseline underneath.
                    de.ledgerline.app.ui.common.SignaturePad(
                        controller = bigController,
                        modifier = Modifier.fillMaxWidth().weight(1f).cardSurface(padded = false),
                        initialUri = signatureUri,
                    )
                    Text(stringResource(R.string.finance_eg_sig_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun grundLabel(g: String): String = stringResource(
    when (g) {
        "privatentnahme" -> R.string.finance_eg_grund_privatentnahme
        "privateinlage" -> R.string.finance_eg_grund_privateinlage
        "trinkgeld" -> R.string.finance_eg_grund_trinkgeld
        "betriebsausgabe" -> R.string.finance_eg_grund_betriebsausgabe
        "sachgeschenk" -> R.string.finance_eg_grund_sachgeschenk
        else -> R.string.finance_eg_grund_sonstiges
    },
)

private fun egTrim(v: Double) = if (v == kotlin.math.floor(v)) v.toLong().toString() else v.toString()
