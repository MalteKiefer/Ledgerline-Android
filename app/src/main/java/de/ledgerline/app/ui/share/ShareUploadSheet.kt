package de.ledgerline.app.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.R
import de.ledgerline.app.core.ModuleAccess
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.ShareInbox
import de.ledgerline.app.data.files.FilesRepository
import de.ledgerline.app.data.gallery.GalleryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Uploads the [ShareInbox] items into Files (root) or Gallery, then clears the inbox. */
@HiltViewModel
class ShareUploadViewModel @Inject constructor(
    private val shareInbox: ShareInbox,
    private val filesRepo: FilesRepository,
    private val galleryRepo: GalleryRepository,
    moduleAccess: ModuleAccess,
) : ViewModel() {
    val items: StateFlow<List<ShareInbox.Item>> = shareInbox.items
    val galleryAllowed: StateFlow<Set<String>?> = moduleAccess.allowed

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Any shared item that Gallery can accept (image/video). */
    fun hasMedia(): Boolean = shareInbox.items.value.any {
        it.mime?.startsWith("image/") == true || it.mime?.startsWith("video/") == true
    }

    fun uploadToFiles(done: (Int) -> Unit) = viewModelScope.launch {
        _busy.value = true
        var ok = 0
        shareInbox.items.value.forEach { if (filesRepo.upload(it.file, it.name, it.mime, null) is Outcome.Ok) ok++ }
        _busy.value = false
        shareInbox.clear()
        done(ok)
    }

    /** Only image/video items go to the Gallery; others are ignored (Gallery rejects non-media). */
    fun uploadToGallery(done: (Int) -> Unit) = viewModelScope.launch {
        _busy.value = true
        var ok = 0
        shareInbox.items.value
            .filter { it.mime?.startsWith("image/") == true || it.mime?.startsWith("video/") == true }
            .forEach { if (galleryRepo.upload(it.file, it.name, it.mime) is Outcome.Ok) ok++ }
        _busy.value = false
        shareInbox.clear()
        done(ok)
    }

    fun dismiss() = shareInbox.clear()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareUploadSheet(vm: ShareUploadViewModel = hiltViewModel()) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val allowed by vm.galleryAllowed.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    if (items.isEmpty()) return

    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var msg by remember { mutableStateOf<String?>(null) }
    val galleryOn = (allowed == null || "gallery" in allowed.orEmpty()) && vm.hasMedia()

    ModalBottomSheet(onDismissRequest = { vm.dismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.share_upload_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.share_upload_count, items.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            items.take(5).forEach { Text("• ${it.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            if (busy) CircularProgressIndicator()
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.uploadToFiles { n -> msg = ctx.getString(R.string.share_uploaded_n, n) } }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.share_to_files))
                }
                if (galleryOn) OutlinedButton(onClick = { vm.uploadToGallery { n -> msg = ctx.getString(R.string.share_uploaded_n, n) } }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.share_to_gallery))
                }
            }
            TextButton(onClick = { vm.dismiss() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}
