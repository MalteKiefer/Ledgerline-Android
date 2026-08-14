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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.gallery_person_name)) },
            text = { TextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.gallery_person_name)) }) },
            confirmButton = { TextButton(onClick = { renaming = null; vm.renamePerson(p.id, name.trim().ifBlank { null }) { reload() } }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    merging?.let { from ->
        val others = (people ?: emptyList()).filter { it.id != from.id }
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text(stringResource(R.string.gallery_person_merge_into)) },
            text = {
                Column {
                    if (others.isEmpty()) Text(stringResource(R.string.gallery_people_empty))
                    others.forEach { target ->
                        Text(
                            target.name ?: stringResource(R.string.gallery_person_unnamed),
                            Modifier.fillMaxWidth().clickable {
                                merging = null
                                vm.mergePeople(from.id, target.id) { reload() }
                            }.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { merging = null }) { Text(stringResource(R.string.action_close)) } },
        )
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
                DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; onRename() })
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
    var photos by remember(person.id) { mutableStateOf<List<GalleryPhoto>?>(null) }
    var lightbox by remember { mutableStateOf<GalleryPhoto?>(null) }
    fun reload() { scope.launch { photos = vm.personPhotos(person.id) } }
    LaunchedEffect(person.id) { reload() }

    lightbox?.let { p -> GalleryLightbox(vm, p, onClose = { lightbox = null }); return }

    AppScaffold(topBar = { AppTopBar(title = person.name ?: stringResource(R.string.gallery_person_unnamed), onBack = onBack) }) { pad ->
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
}
