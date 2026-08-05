package de.ledgerline.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import de.ledgerline.app.R
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.IconChip
import de.ledgerline.app.ui.theme.cardSurface
import de.ledgerline.app.ui.workspace.WorkspaceDest
import de.ledgerline.app.ui.workspace.common.humanSize

/**
 * The Home hub: a post-unlock landing that surfaces storage + security status, quick actions,
 * and "spaces" tiles into the secondary modules. Pure navigation surface — every action routes
 * out via [onOpen].
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpen: (WorkspaceDest) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val counts by vm.counts.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    de.ledgerline.app.ui.common.PullRefresh(onRefresh = { vm.refresh() }, modifier = modifier) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 120.dp),
    ) {
        // --- storage + security ---
        Row(Modifier.fillMaxWidth().cardSurface().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                val used = usage?.first ?: 0L
                val quota = usage?.second ?: 0L
                val frac = if (quota > 0) (used.toFloat() / quota).coerceIn(0f, 1f) else 0f
                CircularProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.size(84.dp),
                    strokeWidth = 7.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        humanSize(used),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (quota > 0) stringResource(R.string.home_of_quota, humanSize(quota)) else stringResource(R.string.home_unlimited),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Verified, null, tint = Brand.tintGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.home_vault_unlocked), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(stringResource(R.string.home_e2e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // --- overdue todos alert ---
        if (counts.todosOverdue > 0) {
            Spacer(Modifier.size(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp))
                    .clickable { onOpen(WorkspaceDest.Todos) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.EventBusy, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    stringResource(R.string.home_overdue_todos, counts.todosOverdue),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // --- quick actions ---
        SectionLabel(stringResource(R.string.home_quick_actions))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Icons.AutoMirrored.Outlined.NoteAdd, stringResource(R.string.home_qa_note), Modifier.weight(1f)) { onOpen(WorkspaceDest.Notes) }
            QuickAction(Icons.Outlined.CloudUpload, stringResource(R.string.home_qa_upload), Modifier.weight(1f)) { onOpen(WorkspaceDest.Files) }
            QuickAction(Icons.Outlined.PhotoCamera, stringResource(R.string.home_qa_backup), Modifier.weight(1f)) { onOpen(WorkspaceDest.Photos) }
            QuickAction(Icons.Outlined.Lock, stringResource(R.string.home_qa_password), Modifier.weight(1f)) { onOpen(WorkspaceDest.Vault) }
        }

        // --- spaces ---
        SectionLabel(stringResource(R.string.home_spaces))
        // Two rows of tiles, non-scrolling (the whole hub scrolls).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SpaceTile(Icons.Outlined.Description, Brand.tintTeal, stringResource(R.string.tab_notes), counts.notes, Modifier.weight(1f)) { onOpen(WorkspaceDest.Notes) }
            SpaceTile(Icons.Outlined.CheckCircle, Brand.tintGreen, stringResource(R.string.tab_todos), counts.todosOpen, Modifier.weight(1f)) { onOpen(WorkspaceDest.Todos) }
        }
        Spacer(Modifier.size(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SpaceTile(Icons.Outlined.Bookmarks, Brand.tintOrange, stringResource(R.string.menu_bookmarks), counts.bookmarks, Modifier.weight(1f)) { onOpen(WorkspaceDest.Bookmarks) }
            SpaceTile(Icons.Outlined.Contacts, Brand.tintBlue, stringResource(R.string.menu_contacts), counts.contacts, Modifier.weight(1f)) { onOpen(WorkspaceDest.Contacts) }
        }
    }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp),
    )
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .cardSurface(padded = false)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Brand.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.size(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SpaceTile(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, name: String, count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.cardSurface().clickable(onClick = onClick).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(icon, tint = tint)
        Spacer(Modifier.size(11.dp))
        Column {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
