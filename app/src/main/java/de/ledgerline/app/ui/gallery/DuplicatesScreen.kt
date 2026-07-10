package de.ledgerline.app.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.ui.ops.OpProgressOverlay
import de.ledgerline.app.ui.workspace.common.CenteredMessage

/**
 * Full-screen duplicate-photo review, entered from the Gallery overflow action. Runs
 * an on-device scan (auto on first entry), lists each duplicate group as a small grid,
 * lets the user mark copies for deletion (per-group "mark rest" or tap-to-toggle) and
 * soft-trashes all marked photos. Progress comes from the shared [OpProgressOverlay].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    modifier: Modifier = Modifier,
    galleryVm: GalleryViewModel,
    dupVm: DuplicatesViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val groups by dupVm.groups.collectAsStateWithLifecycle()
    val scanned by dupVm.scanned.collectAsStateWithLifecycle()
    val marked by dupVm.marked.collectAsStateWithLifecycle()

    // Auto-scan once on first entry.
    LaunchedEffect(Unit) {
        if (!scanned) dupVm.scan()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.duplicates_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { dupVm.scan() }) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.duplicates_rescan),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                scanned && groups.isEmpty() -> CenteredMessage(stringResource(R.string.duplicates_none))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(groups, key = { it.first().id }) { group ->
                        DuplicateGroupSection(
                            group = group,
                            marked = marked,
                            galleryVm = galleryVm,
                            onToggle = { dupVm.toggleMark(it) },
                            onMarkRest = { dupVm.markRest(group) },
                        )
                    }
                }
            }

            // Delete bar pinned to the bottom while any photo is marked.
            if (marked.isNotEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { dupVm.trashMarked() }) {
                            Text(stringResource(R.string.duplicates_trash_marked, marked.size))
                        }
                    }
                }
            }

            // Shared scan progress overlay.
            OpProgressOverlay()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DuplicateGroupSection(
    group: List<GalleryPhoto>,
    marked: Set<String>,
    galleryVm: GalleryViewModel,
    onToggle: (String) -> Unit,
    onMarkRest: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.duplicates_copies, group.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onMarkRest) {
                Text(stringResource(R.string.duplicates_mark_rest))
            }
        }
        // A non-lazy FlowRow so the section can live inside the outer LazyColumn.
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 1.dp)) {
            group.forEach { photo ->
                DupMarkCell(
                    photo = photo,
                    galleryVm = galleryVm,
                    marked = photo.id in marked,
                    onClick = { onToggle(photo.id) },
                    modifier = Modifier.size(116.dp),
                )
            }
        }
    }
}

@Composable
private fun DupMarkCell(
    photo: GalleryPhoto,
    galleryVm: GalleryViewModel,
    marked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, photo.id) {
        value = galleryVm.thumb(photo)
    }
    Box(
        modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        }
        if (photo.media_type == "video") {
            Text("▶", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurface)
        }
        if (marked) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
        }
    }
}
