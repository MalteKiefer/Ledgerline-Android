package de.ledgerline.app.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.gallery.GalleryPerson
import de.ledgerline.app.domain.model.gallery.GalleryPhoto
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import kotlinx.coroutines.launch

/** Face-recognition people: cover-face avatars with rename / delete / merge; open one into its photos. */
@Composable
fun GalleryPeopleScreen(vm: GalleryViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf<List<GalleryPerson>?>(null) }
    var opened by remember { mutableStateOf<GalleryPerson?>(null) }
    var renaming by remember { mutableStateOf<GalleryPerson?>(null) }
    var merging by remember { mutableStateOf<GalleryPerson?>(null) }
    fun reload() { scope.launch { people = vm.people() } }
    LaunchedEffect(Unit) { reload() }

    opened?.let { p -> GalleryPersonPhotosScreen(vm, p, onBack = { opened = null }); return }

    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.gallery_people), onBack = onBack) }) { pad ->
        val list = people
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.gallery_people_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.fillMaxSize().padding(pad).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { person ->
                    PersonCard(
                        vm, person,
                        onOpen = { opened = person },
                        onRename = { renaming = person },
                        onDelete = { vm.deletePerson(person.id) { reload() } },
                        onMerge = { merging = person },
                    )
                }
            }
        }
    }

    renaming?.let { p -> NamePersonDialog(vm, p, onDone = { reload() }, onDismiss = { renaming = null }) }
    merging?.let { from ->
        MergePersonDialog(vm, from, others = (people ?: emptyList()).filter { it.id != from.id }, onMerged = { reload() }, onDismiss = { merging = null })
    }
}

@Composable
private fun PersonCard(vm: GalleryViewModel, person: GalleryPerson, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onMerge: () -> Unit) {
    var face by remember(person.coverFaceId) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(person.coverFaceId) { face = vm.faceCrop(person.coverFaceId) }
    var menu by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onOpen() }, contentAlignment = Alignment.Center) {
            if (face != null) Image(face!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Outlined.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        }
        Box {
            Text(
                (person.name ?: stringResource(R.string.gallery_person_unnamed)),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { menu = true }.padding(top = 4.dp),
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.gallery_person_name_link)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.gallery_person_merge_into)) }, onClick = { menu = false; onMerge() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
            }
        }
        Text(stringResource(R.string.gallery_person_count, person.count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One person's photos with a lightbox (reuses the shared viewer). */
@Composable
fun GalleryPersonPhotosScreen(vm: GalleryViewModel, person: GalleryPerson, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember(person.id) { mutableStateOf(person) } // updates after rename/link
    var photos by remember(person.id) { mutableStateOf<List<GalleryPhoto>?>(null) }
    var lightbox by remember { mutableStateOf<GalleryPhoto?>(null) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var allPeople by remember { mutableStateOf<List<GalleryPerson>>(emptyList()) }
    fun reload() { scope.launch { photos = vm.personPhotos(current.id); allPeople = vm.people() } }
    LaunchedEffect(person.id) { reload() }
    // Navigate to a (possibly merged/renamed) resulting person: adopt it and reload its combined photos.
    fun goTo(p: GalleryPerson) { current = p; photos = null; reload() }

    lightbox?.let { p -> GalleryLightbox(vm, p, onClose = { lightbox = null }); return }

    AppScaffold(topBar = {
        AppTopBar(
            title = current.name ?: stringResource(R.string.gallery_person_unnamed),
            onBack = onBack,
            actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.gallery_person_name_link)) }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.gallery_person_merge_into)) }, onClick = { menu = false; merging = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; vm.deletePerson(current.id) { onBack() } })
                    }
                }
            },
        )
    }) { pad ->
        val list = photos
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text(stringResource(R.string.gallery_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.fillMaxSize().padding(pad).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(list, key = { it.id }) { p ->
                    var bmp by remember(p.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                    LaunchedEffect(p.id) { bmp = vm.thumbnail(p) }
                    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable { lightbox = p }) {
                        bmp?.let { Image(it, contentDescription = p.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
            }
        }
    }

    if (renaming) NamePersonDialog(vm, current, onDone = { p -> p?.let { goTo(it) } }, onDismiss = { renaming = false })
    if (merging) MergePersonDialog(vm, current, others = allPeople.filter { it.id != current.id }, onMerged = { target -> goTo(target) }, onDismiss = { merging = false })
}

/**
 * One dialog to name AND link a person (web parity): type a name — free text saves as the name,
 * matching contacts are suggested live and tapping one links the person (adopting the contact's name
 * when the person is still unnamed, keeping the typed/existing name otherwise).
 */
@Composable
private fun NamePersonDialog(vm: GalleryViewModel, person: GalleryPerson, onDone: (GalleryPerson?) -> Unit, onDismiss: () -> Unit) {
    var q by remember(person.id) { mutableStateOf(person.name ?: "") }
    var contacts by remember(person.id) { mutableStateOf<List<de.ledgerline.app.data.remote.ContactLite>?>(null) }
    LaunchedEffect(person.id, q) { contacts = vm.searchContacts(q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gallery_person_name_link)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                TextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.gallery_contact_search_hint)) })
                if (person.contactId != null) Text(
                    stringResource(R.string.gallery_person_unlink),
                    Modifier.fillMaxWidth().clickable { onDismiss(); vm.linkPersonContact(person.id, null, null) { onDone(it) } }.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (val list = contacts) {
                    null -> if (q.trim().length >= 2) Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> if (list.isNotEmpty()) {
                        Text(stringResource(R.string.gallery_contact_suggestions), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn {
                            listItems(list, key = { it.id }) { c ->
                                Text(
                                    c.display,
                                    Modifier.fillMaxWidth().clickable {
                                        onDismiss()
                                        // Keep an existing custom name; else adopt the contact's name.
                                        vm.linkPersonContact(person.id, c.id, person.name?.takeIf { it.isNotBlank() }) { onDone(it) }
                                    }.padding(vertical = 10.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }
            }
        },
        // Free-text save = rename (no contact link).
        confirmButton = { TextButton(enabled = q.isNotBlank(), onClick = { onDismiss(); vm.renamePerson(person.id, q.trim().ifBlank { null }) { onDone(it) } }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MergePersonDialog(vm: GalleryViewModel, person: GalleryPerson, others: List<GalleryPerson>, onMerged: (GalleryPerson) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    // Only NAMED people are valid merge targets; filter by the typed query (autocomplete).
    val named = remember(others) { others.filter { !it.name.isNullOrBlank() } }
    val matches = remember(named, q) {
        if (q.isBlank()) named else named.filter { it.name!!.contains(q, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gallery_person_merge_into)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                TextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.gallery_person_name)) })
                when {
                    named.isEmpty() -> Text(stringResource(R.string.gallery_person_merge_none), Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    matches.isEmpty() -> Text(stringResource(R.string.gallery_people_empty), Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn {
                        listItems(matches, key = { it.id }) { target ->
                            Text(
                                target.name!!,
                                Modifier.fillMaxWidth().clickable { onDismiss(); vm.mergePeople(person.id, target.id) { ok -> if (ok) onMerged(target) } }.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
