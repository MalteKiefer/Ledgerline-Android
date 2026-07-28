package de.ledgerline.app.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.core.health.HealthFasting
import de.ledgerline.app.domain.model.HealthFast
import de.ledgerline.app.domain.model.HealthProfile
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.PrimaryGradientButton
import de.ledgerline.app.ui.theme.SecondaryBrandButton
import de.ledgerline.app.ui.theme.cardSurface

// ---- Fasting ---------------------------------------------------------------

@Composable
internal fun FastingCard(
    active: HealthFast?,
    history: List<HealthFast>,
    nowMs: Long,
    onStart: (Int) -> Unit,
    onStop: (HealthFast) -> Unit,
    onEditFast: (HealthFast) -> Unit,
    onDeleteFast: (HealthFast) -> Unit,
) {
    Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.health_fasting), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        if (active != null) {
            val p = HealthFasting.progress(active, nowMs)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { p.fraction.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.size(84.dp),
                        strokeWidth = 7.dp,
                        color = if (p.reached) Brand.tintGreen else Brand.accent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        "${HealthFasting.pct(active, nowMs)}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(HealthFasting.formatDurationHMS(p.elapsed), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.health_fast_target) + ": " + HealthFasting.templateLabel(active.targetHours) +
                            " · " + HealthFasting.formatDuration(p.target),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (p.reached) {
                        Text(stringResource(R.string.health_fast_reached), style = MaterialTheme.typography.labelMedium, color = Brand.tintGreen, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            SecondaryBrandButton(stringResource(R.string.health_fast_stop), onClick = { onStop(active) })
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HealthFasting.TEMPLATES.forEach { t ->
                    AssistChip(onClick = { onStart(t.targetHours) }, label = { Text(t.key) }, modifier = Modifier.weight(1f))
                }
            }
            PrimaryGradientButton(stringResource(R.string.health_fast_start), onClick = { onStart(16) })
        }

        if (history.isNotEmpty()) {
            Text(stringResource(R.string.health_fast_history), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            history.take(10).forEach { f ->
                val endMs = try { java.time.Instant.parse(f.end).toEpochMilli() } catch (_: Exception) { nowMs }
                val p = HealthFasting.progress(f, endMs)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp)) // spacer alignment
                    Column(Modifier.weight(1f)) {
                        Text(
                            HealthFasting.formatDuration(p.elapsed) + " · " + HealthFasting.templateLabel(f.targetHours),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        )
                        Text(formatEntryTime(f.start), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (p.reached) {
                        Box(Modifier.size(8.dp)) // reached marker handled by text below on small screens
                        Text("✓", color = Brand.tintGreen, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = { onEditFast(f) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.health_fast_edit), modifier = Modifier.size(18.dp)) }
                    IconButton(onClick = { onDeleteFast(f) }) { Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}

// ---- Master data -----------------------------------------------------------

@Composable
internal fun MasterDataCard(age: Int?, bmi: Double?, profile: HealthProfile, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().cardSurface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.health_master), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.health_master), modifier = Modifier.size(20.dp)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BigStat(stringResource(R.string.health_age), age?.let { stringResource(R.string.health_age_years, it) } ?: "—")
            BigStat(stringResource(R.string.health_bmi), bmi?.let { HealthComputeFmt(it) } ?: "—")
            profile.heightCm?.let { BigStat(stringResource(R.string.health_height), "${it.toInt()}") }
        }
    }
}

@Composable
private fun BigStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** BMI formatting (1 dp, integer without .0). */
private fun HealthComputeFmt(d: Double): String = de.ledgerline.app.core.health.HealthCompute.fmt(d)
