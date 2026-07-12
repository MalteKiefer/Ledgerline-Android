package de.ledgerline.app.ui.workspace.bookmarks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.Bookmark
import de.ledgerline.app.ui.common.ConfirmDialog
import de.ledgerline.app.ui.common.openUrl
import de.ledgerline.app.ui.workspace.LocalFullscreen
import de.ledgerline.app.ui.workspace.common.DetailHero
import de.ledgerline.app.ui.workspace.common.DetailQuickAction
import de.ledgerline.app.ui.workspace.common.InfoCard
import de.ledgerline.app.ui.workspace.common.InfoRow
import de.ledgerline.app.ui.workspace.common.RowDivider
import de.ledgerline.app.ui.workspace.common.TagChips

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkDetailScreen(
    bookmark: Bookmark,
    folderName: String?,
    linkChooser: Boolean,
    onEdit: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleReadLater: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    val url = bookmark.url.trim()
    val host = runCatching { url.toUri().host }.getOrNull()?.removePrefix("www.").orEmpty()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_bookmarks)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (bookmark.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                            stringResource(R.string.bm_favorite),
                            tint = if (bookmark.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, stringResource(R.string.bm_edit))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.bm_delete))
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
            DetailHero(
                title = bookmark.title.ifBlank { host.ifBlank { url } },
                subtitle = host.takeIf { it.isNotBlank() && bookmark.title.isNotBlank() },
                leading = {
                    Icon(
                        Icons.Outlined.Bookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                },
            )

            if (url.startsWith("http://") || url.startsWith("https://")) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                ) {
                    DetailQuickAction(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.bm_action_open)) {
                        openUrl(context, url, linkChooser)
                    }
                    DetailQuickAction(Icons.Outlined.ContentCopy, stringResource(R.string.bm_action_copy)) {
                        val clip = context.getSystemService(ClipboardManager::class.java)
                        clip?.setPrimaryClip(ClipData.newPlainText("url", url))
                    }
                    DetailQuickAction(Icons.Outlined.Share, stringResource(R.string.bm_action_share)) {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                        runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    }
                    DetailQuickAction(Icons.Outlined.Schedule, stringResource(R.string.bm_read_later), onToggleReadLater)
                }
            }

            InfoCard {
                InfoRow(Icons.Outlined.Language, url, "URL") { openUrl(context, url, linkChooser) }
                if (!folderName.isNullOrBlank()) {
                    RowDivider()
                    InfoRow(Icons.Outlined.Folder, folderName, stringResource(R.string.bm_folder))
                }
            }

            if (bookmark.description.isNotBlank()) {
                InfoCard {
                    InfoRow(Icons.Outlined.Notes, bookmark.description, null)
                }
            }

            if (bookmark.tags.isNotEmpty()) {
                TagChips(bookmark.tags, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            message = stringResource(R.string.bm_delete),
            confirmLabel = stringResource(R.string.bm_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
