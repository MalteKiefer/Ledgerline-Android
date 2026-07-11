package de.ledgerline.app.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.NamedFolder
import de.ledgerline.app.ui.ops.OpProgressOverlay
import de.ledgerline.app.ui.ops.OpsViewModel

/**
 * Share-target confirm sheet. Summarises the classified items, lets the user pick a
 * target folder for file items, and kicks off the import via [ShareViewModel]. The
 * shared [OpProgressOverlay] renders progress; once an import has started and the
 * operation queue drains, [onDone] is invoked to finish the hosting activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    items: List<SharedItem>,
    vm: ShareViewModel = hiltViewModel(),
    onDone: () -> Unit,
) {
    val opsVm: OpsViewModel = hiltViewModel()
    val activeOps by opsVm.active.collectAsStateWithLifecycle()
    val folders by vm.fileFolders.collectAsStateWithLifecycle()

    val photos = items.count { it.target == ShareTarget.GALLERY }
    val files = items.count { it.target == ShareTarget.FILES }

    var started by remember { mutableStateOf(false) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var folderMenuOpen by remember { mutableStateOf(false) }

    // Completion: once an import has actually started and the op queue has drained,
    // finish. The message (if any) is surfaced by the activity as a Toast first.
    LaunchedEffect(started, activeOps) {
        if (started && activeOps.isEmpty()) onDone()
    }

    val rootLabel = stringResource(R.string.share_root)
    val selectedName = folders.firstOrNull { it.id == selectedFolder }?.name ?: rootLabel

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.share_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    if (photos > 0) {
                        Text(
                            stringResource(R.string.share_summary_photos, photos),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (files > 0) {
                        if (photos > 0) Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.share_summary_files, files),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    // Folder selector — only relevant when there are file items.
                    if (files > 0) {
                        Spacer(Modifier.height(16.dp))
                        ExposedDropdownMenuBox(
                            expanded = folderMenuOpen,
                            onExpandedChange = { folderMenuOpen = it },
                        ) {
                            OutlinedTextField(
                                value = selectedName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.share_target_folder)) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = folderMenuOpen)
                                },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                            )
                            DropdownMenu(
                                expanded = folderMenuOpen,
                                onDismissRequest = { folderMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(rootLabel) },
                                    onClick = { selectedFolder = null; folderMenuOpen = false },
                                )
                                folders.forEach { folder: NamedFolder ->
                                    DropdownMenuItem(
                                        text = { Text(folder.name) },
                                        onClick = { selectedFolder = folder.id; folderMenuOpen = false },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onDone,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                        }
                        Button(
                            onClick = {
                                started = true
                                vm.import(items, selectedFolder)
                            },
                            enabled = !started,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(stringResource(R.string.share_import), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        OpProgressOverlay()
    }
}
