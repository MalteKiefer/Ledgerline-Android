package de.ledgerline.app.ui.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
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
    // Signature strokes (screen-space points); rasterized to PNG on save.
    val strokes = remember { mutableListOf<MutableList<Offset>>().toMutableStateList() }
    var busy by remember { mutableStateOf(false) }
    var canvasW by remember { mutableStateOf(1) }
    var canvasH by remember { mutableStateOf(1) }

    fun draft() = Eigenbeleg.Draft(
        grund = grund, grundOther = grundOther.trim(), recipient = recipient.trim(), address = address.trim(),
        ort = ort.trim(), date = date.trim(), createdAt = initial.createdAt,
        buchungstext = buchungstext.trim(), gross = gross.replace(',', '.').toDoubleOrNull() ?: 0.0,
        vatRate = vatRate.replace(',', '.').toDoubleOrNull() ?: 0.0, reason = reason.trim(), issuer = issuer.trim(),
        signature = if (strokes.any { it.size > 1 }) SignatureRaster.toDataUri(strokes, canvasW, canvasH) else "",
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
                    IconButton(onClick = { strokes.clear() }) { Icon(Icons.Outlined.Clear, stringResource(R.string.finance_eg_sig_clear)) }
                }
                // Signature pad.
                Canvas(
                    Modifier.fillMaxWidth().height(140.dp)
                        .cardSurface(padded = false)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { strokes.add(mutableListOf(it)) },
                                onDrag = { change, _ -> strokes.lastOrNull()?.add(change.position) },
                            )
                        },
                ) {
                    canvasW = size.width.toInt(); canvasH = size.height.toInt()
                    strokes.forEach { pts ->
                        if (pts.size > 1) {
                            val p = Path().apply { moveTo(pts.first().x, pts.first().y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                            drawPath(p, Color(0xFF17161F), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.5f))
                        }
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
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

/** Rasterizes captured signature strokes to a PNG `data:` URI (transparent bg, black ink). */
private object SignatureRaster {
    fun toDataUri(strokes: List<List<Offset>>, w: Int, h: Int): String {
        if (w <= 1 || h <= 1) return ""
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK; strokeWidth = 4f; style = android.graphics.Paint.Style.STROKE
            isAntiAlias = true; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
        }
        strokes.forEach { pts ->
            if (pts.size > 1) {
                val path = android.graphics.Path().apply {
                    moveTo(pts.first().x, pts.first().y); pts.drop(1).forEach { lineTo(it.x, it.y) }
                }
                cv.drawPath(path, paint)
            }
        }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }
}
