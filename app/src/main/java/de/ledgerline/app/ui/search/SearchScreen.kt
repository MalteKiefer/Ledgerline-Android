package de.ledgerline.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.theme.IconChip
import de.ledgerline.app.ui.workspace.WorkspaceDest

/** Full-screen global search. Routes a hit to its module via [onOpen]; [onBack] closes. */
@Composable
fun SearchScreen(
    onOpen: (WorkspaceDest) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SearchViewModel = hiltViewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
            TextField(
                value = query,
                onValueChange = vm::setQuery,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                placeholder = { Text(stringResource(R.string.search_everything)) },
                leadingIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
                trailingIcon = { Icon(Icons.Outlined.Search, null) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            if (query.isNotBlank() && results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.search_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    items(results) { hit ->
                        ListItem(
                            headlineContent = { Text(hit.title) },
                            supportingContent = { Text(labelOf(hit.dest) + " · " + hit.subtitle) },
                            leadingContent = { IconChip(iconOf(hit.dest), tint = tintOf(hit.dest)) },
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(hit.dest) },
                        )
                    }
                }
            }
        }
    }
}

@Composable private fun labelOf(d: WorkspaceDest) = stringResource(d.labelRes)

private fun iconOf(d: WorkspaceDest): ImageVector = when (d) {
    WorkspaceDest.Files -> Icons.Outlined.Folder
    WorkspaceDest.Photos -> Icons.Outlined.PhotoLibrary
    WorkspaceDest.Vault -> Icons.Outlined.Lock
    WorkspaceDest.Notes -> Icons.Outlined.Description
    WorkspaceDest.Bookmarks -> Icons.Outlined.Bookmarks
    WorkspaceDest.Contacts -> Icons.Outlined.Contacts
    else -> Icons.Outlined.Search
}

private fun tintOf(d: WorkspaceDest): Color = when (d) {
    WorkspaceDest.Files -> Brand.tintBlue
    WorkspaceDest.Photos -> Brand.tintViolet
    WorkspaceDest.Vault -> Brand.tintViolet
    WorkspaceDest.Notes -> Brand.tintTeal
    WorkspaceDest.Bookmarks -> Brand.tintOrange
    WorkspaceDest.Contacts -> Brand.tintBlue
    else -> Brand.tintGray
}
