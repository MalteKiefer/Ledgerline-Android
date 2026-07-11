package de.ledgerline.app.ui.workspace.contacts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.Contact
import de.ledgerline.app.ui.common.ConfirmDialog
import de.ledgerline.app.ui.workspace.common.ErrorBox
import de.ledgerline.app.ui.workspace.common.LoadingBox
import de.ledgerline.app.ui.workspace.common.RefreshableMessage
import de.ledgerline.app.ui.workspace.common.SearchField
import de.ledgerline.app.ui.workspace.common.TagFilterRow
import de.ledgerline.app.ui.workspace.common.TrashBar

/** The display name shown in list rows / detail. */
internal fun contactDisplayName(c: Contact): String =
    c.fn.ifBlank { "${c.first} ${c.last}".trim() }.ifBlank { c.org }

/** Up to two initials from the display name, for the avatar placeholder. */
internal fun contactInitials(c: Contact): String {
    val name = contactDisplayName(c)
    val parts = name.split(' ', '\t').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(modifier: Modifier = Modifier, vm: ContactsViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val showTrash by vm.showTrash.collectAsStateWithLifecycle()
    val trashCount by vm.trashCount.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val favoritesOnly by vm.favoritesOnly.collectAsStateWithLifecycle()
    val categories by vm.allCategories.collectAsStateWithLifecycle()
    val activeCategory by vm.activeCategory.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var openId by remember { mutableStateOf<String?>(null) }
    var pendingNew by remember { mutableStateOf<Contact?>(null) }
    var deleteForeverTarget by remember { mutableStateOf<String?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    // Detail/editor takes over the whole screen.
    val current = openId
    if (current != null) {
        val contact = vm.contactById(current) ?: pendingNew?.takeIf { it.id == current }
        if (contact != null) {
            ContactDetailScreen(
                contact = contact,
                isNew = pendingNew?.id == current,
                loadAvatar = { vm.avatar(it) },
                onSave = { updated -> vm.saveContact(contact.id, updated) },
                onToggleFavorite = { vm.toggleFavorite(contact.id) },
                onDelete = { vm.trash(contact.id); openId = null; pendingNew = null },
                onPickAvatar = { bytes -> vm.pickAvatar(contact.id, bytes) },
                onRemoveAvatar = { vm.removeAvatar(contact.id) },
                onBack = { openId = null; pendingNew = null },
                modifier = modifier,
            )
            return
        } else {
            openId = null
            pendingNew = null
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!ui.loading && !ui.error && !showTrash) {
                FloatingActionButton(onClick = { pendingNew = vm.newBlankContact().also { openId = it.id } }) {
                    Icon(Icons.Outlined.PersonAdd, stringResource(R.string.contact_new))
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading -> LoadingBox()
                ui.error -> ErrorBox(stringResource(R.string.ws_error), onRetry = { vm.refresh() })
                else -> Column(Modifier.fillMaxSize()) {
                    if (showTrash) {
                        TrashBar(
                            onBack = { vm.setTrash(false) },
                            onEmptyTrash = { confirmEmptyTrash = true },
                            emptyEnabled = ui.contacts.isNotEmpty(),
                        )
                    } else if (trashCount > 0) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                            TextButton(onClick = { vm.setTrash(true) }) {
                                Icon(Icons.Outlined.DeleteOutline, null, Modifier.padding(end = 4.dp).size(18.dp))
                                Text(stringResource(R.string.trash_open, trashCount))
                            }
                            TextButton(onClick = { vm.toggleFavoritesOnly() }) {
                                Icon(
                                    if (favoritesOnly) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                    null, Modifier.padding(end = 4.dp).size(18.dp),
                                )
                                Text(stringResource(R.string.contact_favorite))
                            }
                        }
                    } else if (!showTrash) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                            TextButton(onClick = { vm.toggleFavoritesOnly() }) {
                                Icon(
                                    if (favoritesOnly) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                    null, Modifier.padding(end = 4.dp).size(18.dp),
                                )
                                Text(stringResource(R.string.contact_favorite))
                            }
                        }
                    }
                    if (!showTrash) SearchField(query = query, onQueryChange = { vm.setQuery(it) })
                    if (!showTrash) {
                        TagFilterRow(
                            tags = categories,
                            activeTag = activeCategory,
                            onSelect = { vm.setActiveCategory(it) },
                        )
                    }
                    val emptyText = if (showTrash) R.string.trash_empty_state
                    else if (query.isNotBlank()) R.string.search_no_results
                    else R.string.contacts_empty
                    PullToRefreshBox(ui.loading, { vm.refresh() }, Modifier.weight(1f)) {
                        if (ui.contacts.isEmpty()) {
                            RefreshableMessage(stringResource(emptyText))
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(ui.contacts, key = { it.id }) { c ->
                                    if (showTrash) {
                                        TrashContactRow(
                                            contact = c,
                                            onRestore = { vm.restore(c.id) },
                                            onDeleteForever = { deleteForeverTarget = c.id },
                                        )
                                    } else {
                                        ContactRow(
                                            contact = c,
                                            loadAvatar = { vm.avatar(it) },
                                            onOpen = { openId = c.id },
                                            onToggleFavorite = { vm.toggleFavorite(c.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteForeverTarget?.let { id ->
        ConfirmDialog(
            message = stringResource(R.string.delete_forever_confirm),
            confirmLabel = stringResource(R.string.action_delete_forever),
            onConfirm = { vm.deleteForever(id); deleteForeverTarget = null },
            onDismiss = { deleteForeverTarget = null },
        )
    }

    if (confirmEmptyTrash) {
        ConfirmDialog(
            message = stringResource(R.string.trash_empty_confirm),
            confirmLabel = stringResource(R.string.trash_empty),
            onConfirm = { vm.emptyTrash(); confirmEmptyTrash = false },
            onDismiss = { confirmEmptyTrash = false },
        )
    }
}

@Composable
internal fun ContactAvatar(
    contact: Contact,
    loadAvatar: suspend (Contact) -> android.graphics.Bitmap?,
    size: androidx.compose.ui.unit.Dp,
) {
    val bmp by produceState<android.graphics.Bitmap?>(null, contact.avatarRef) {
        value = if (contact.avatarRef != null) loadAvatar(contact) else null
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val image = bmp
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        } else {
            Text(
                contactInitials(contact),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactRow(
    contact: Contact,
    loadAvatar: suspend (Contact) -> android.graphics.Bitmap?,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val subtitle = listOf(contact.org, contact.title).filter { it.isNotBlank() }.joinToString(" · ")
    ListItem(
        headlineContent = {
            Text(contactDisplayName(contact).ifBlank { stringResource(R.string.contact_untitled) })
        },
        supportingContent = if (subtitle.isNotBlank()) {
            { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
        leadingContent = { ContactAvatar(contact, loadAvatar, 40.dp) },
        trailingContent = {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (contact.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    stringResource(R.string.contact_favorite),
                    tint = if (contact.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashContactRow(contact: Contact, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(contactDisplayName(contact).ifBlank { stringResource(R.string.contact_untitled) })
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRestore) {
                    Icon(Icons.Outlined.RestoreFromTrash, stringResource(R.string.action_restore))
                }
                IconButton(onClick = onDeleteForever) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete_forever))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
