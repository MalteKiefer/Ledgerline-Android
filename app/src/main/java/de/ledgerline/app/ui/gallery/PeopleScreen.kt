package de.ledgerline.app.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.core.ops.OpKind
import de.ledgerline.app.domain.model.GalleryPerson
import de.ledgerline.app.ui.ops.OpProgressOverlay
import de.ledgerline.app.ui.ops.OpsViewModel
import de.ledgerline.app.ui.workspace.common.CenteredMessage

@Composable
fun PeopleScreen(
    modifier: Modifier = Modifier,
    galleryVm: GalleryViewModel,
    peopleVm: PeopleViewModel = hiltViewModel(),
    onOpenPerson: (String) -> Unit,
) {
    val people by peopleVm.people.collectAsStateWithLifecycle()
    val opsVm: OpsViewModel = hiltViewModel()
    val ops by opsVm.active.collectAsStateWithLifecycle()
    val scanning = ops.any { it.kind == OpKind.FACE_SCAN }
    var scanMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<GalleryPerson?>(null) }
    var merging by remember { mutableStateOf<GalleryPerson?>(null) }
    var linking by remember { mutableStateOf<GalleryPerson?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Scan action row.
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(onClick = { scanMenu = true }, enabled = !scanning) {
                    Text(stringResource(R.string.people_scan))
                }
                DropdownMenu(expanded = scanMenu, onDismissRequest = { scanMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.people_scan_all)) },
                        onClick = { scanMenu = false; peopleVm.scanFaces(0) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.people_scan_recent)) },
                        onClick = { scanMenu = false; peopleVm.scanFaces(200) },
                    )
                }
            }

            if (people.isEmpty()) {
                CenteredMessage(stringResource(R.string.people_empty))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(people, key = { it.id }) { person ->
                        PersonCard(
                            person = person,
                            peopleVm = peopleVm,
                            onClick = { onOpenPerson(person.id) },
                            onRename = { renaming = person },
                            onHide = { peopleVm.hide(person) },
                            onMerge = { merging = person },
                            onLink = { linking = person },
                            onUnlink = { peopleVm.unlinkContact(person) },
                        )
                    }
                }
            }
        }

        // Rename dialog.
        val editing = renaming
        if (editing != null) {
            var text by remember(editing.id) { mutableStateOf(editing.name) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text(stringResource(R.string.person_rename)) },
                text = {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.person_name_hint)) },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        peopleVm.rename(editing, text)
                        renaming = null
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { renaming = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }

        // Link this person to a workspace contact (contact picker).
        linking?.let { p ->
            val contacts = remember(people) { peopleVm.contacts() }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { linking = null },
                title = { Text(stringResource(R.string.person_link_contact)) },
                text = {
                    if (contacts.isEmpty()) {
                        Text(stringResource(R.string.contacts_empty_link))
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            contacts.forEach { c ->
                                val label = c.fn.ifBlank { listOf(c.first, c.last).filter { it.isNotBlank() }.joinToString(" ") }
                                Text(
                                    text = label.ifBlank { stringResource(R.string.person_unnamed) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { peopleVm.linkToContact(p, c); linking = null }
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { linking = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }

        // Merge this person into another (target picker).
        merging?.let { src ->
            PersonPickerDialog(
                people = people.filter { it.id != src.id && !it.hidden },
                title = stringResource(R.string.person_merge_into),
                peopleVm = peopleVm,
                onPick = { targetId -> peopleVm.merge(src, targetId); merging = null },
                onDismiss = { merging = null },
            )
        }

        // Shared progress overlay (face scan / uploads / duplicate scan).
        OpProgressOverlay()
    }
}

@Composable
private fun PersonCard(
    person: GalleryPerson,
    peopleVm: PeopleViewModel,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onHide: () -> Unit,
    onMerge: () -> Unit,
    onLink: () -> Unit,
    onUnlink: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val cover = peopleVm.personCover(person)
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, person.id) {
        value = cover?.let { peopleVm.faceThumb(it) }
    }
    val count = peopleVm.personPhotos(person).size

    Column(
        Modifier
            .padding(8.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) {
                Image(
                    b.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Person,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Overflow menu anchored top-end.
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.person_rename)) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.person_merge)) },
                        onClick = { menu = false; onMerge() },
                    )
                    if (person.contactId == null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.person_link_contact)) },
                            onClick = { menu = false; onLink() },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.person_unlink_contact)) },
                            onClick = { menu = false; onUnlink() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.person_hide)) },
                        onClick = { menu = false; onHide() },
                    )
}
            }
        }
        Text(
            text = person.name.ifBlank { stringResource(R.string.person_unnamed) },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(R.string.person_photos_count, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    personId: String,
    modifier: Modifier = Modifier,
    galleryVm: GalleryViewModel,
    peopleVm: PeopleViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val people by peopleVm.people.collectAsStateWithLifecycle()
    val person = remember(personId, people) { peopleVm.personById(personId) }
    var openId by remember { mutableStateOf<String?>(null) }
    var reassignPhotoId by remember { mutableStateOf<String?>(null) }

    val photos = remember(person, people) { person?.let { peopleVm.personPhotos(it) }.orEmpty() }

    // If the person is gone or has no photos, pop back.
    LaunchedEffect(person, photos) {
        if (person == null || photos.isEmpty()) onBack()
    }
    if (person == null || photos.isEmpty()) return

    // Full-screen detail: hide the outer workspace chrome so this screen's own top bar
    // owns the status-bar inset (otherwise content slid under the clock).
    val fs = de.ledgerline.app.ui.workspace.LocalFullscreen.current
    androidx.compose.runtime.DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }

    val current = openId
    if (current != null) {
        val photo = galleryVm.photoById(current)
        if (photo != null) {
            PhotoViewerScreen(photo, galleryVm, onBack = { openId = null }, modifier = modifier)
            return
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(person.name.ifBlank { stringResource(R.string.person_unnamed) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 116.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            items(photos, key = { it.id }) { photo ->
                var cellMenu by remember { mutableStateOf(false) }
                Box {
                    ThumbCell(photo, galleryVm, onLongClick = { cellMenu = true }) { openId = photo.id }
                    DropdownMenu(expanded = cellMenu, onDismissRequest = { cellMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.person_set_cover)) },
                            onClick = { cellMenu = false; peopleVm.setCover(person, photo.id) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.person_remove_face)) },
                            onClick = { cellMenu = false; peopleVm.removeFromPerson(person, photo.id) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.person_reassign_face)) },
                            onClick = { cellMenu = false; reassignPhotoId = photo.id },
                        )
                    }
                }
            }
        }
    }

    // Move a photo's faces from this person to another (target picker).
    reassignPhotoId?.let { pid ->
        PersonPickerDialog(
            people = people.filter { it.id != person.id && !it.hidden },
            title = stringResource(R.string.person_reassign_face),
            peopleVm = peopleVm,
            onPick = { targetId -> peopleVm.reassignFace(person, targetId, pid); reassignPhotoId = null },
            onDismiss = { reassignPhotoId = null },
        )
    }
}

/** Picks a target person from [people] (face-crop avatar + name). Used for reassign + merge. */
@Composable
private fun PersonPickerDialog(
    people: List<GalleryPerson>,
    title: String,
    peopleVm: PeopleViewModel,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (people.isEmpty()) {
                Text(stringResource(R.string.person_none_other))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    people.forEach { p ->
                        Text(
                            text = p.name.ifBlank { stringResource(R.string.person_unnamed) },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(p.id) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
