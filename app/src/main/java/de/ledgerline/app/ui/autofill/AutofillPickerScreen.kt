package de.ledgerline.app.ui.autofill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.core.PasswordsCache
import de.ledgerline.app.core.autofill.DomainMatch
import de.ledgerline.app.data.PasswordsRepository
import de.ledgerline.app.domain.model.SecretFields
import de.ledgerline.app.domain.model.SecretItem
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.theme.IconChip

/**
 * The authenticated Autofill picker: after unlock, lists the vault credentials that match the
 * requesting site/app (falling back to all logins when nothing matches so the user is never
 * stuck), and returns the chosen one to the OS. Runs inside [AutofillUnlockActivity].
 */
@Composable
fun AutofillPickerScreen(
    repo: PasswordsRepository,
    cache: PasswordsCache,
    webDomain: String?,
    packageName: String?,
    onPick: (SecretItem) -> Unit,
    onCancel: () -> Unit,
) {
    val store by cache.value.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(store == null) }

    LaunchedEffect(Unit) {
        if (cache.value.value == null) {
            repo.load()
        }
        loading = false
    }

    val all = store?.manifest?.secrets.orEmpty().filter { !it.isTrashed }
    val matched = all.filter { DomainMatch.matches(it, webDomain, packageName) }
    val fillable = all.filter { it.type in setOf("login", "password", "server") }
    // Anti-phishing: never blindly offer ALL logins on a domain mismatch. Show matches only;
    // browsing the full list is an explicit, searchable opt-in.
    var showAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val label = webDomain ?: packageName ?: ""
    val browseList = fillable.filter {
        query.isBlank() || it.title.lowercase().contains(query.lowercase()) ||
            SecretFields.subtitle(it).lowercase().contains(query.lowercase())
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.autofill_pick_title),
                onBack = onCancel,
            )
        },
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            matched.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(matched, key = { it.id }) { item -> PickRow(item, onPick) }
            }
            !showAll -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.autofill_no_match_for, label))
                    TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.autofill_show_all)) }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text(stringResource(R.string.autofill_pick_title)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(browseList, key = { it.id }) { item -> PickRow(item, onPick) }
                }
            }
        }
    }
}

@Composable
private fun PickRow(item: SecretItem, onPick: (SecretItem) -> Unit) {
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = {
            val sub = SecretFields.subtitle(item)
            if (sub.isNotBlank()) Text(sub)
        },
        leadingContent = { IconChip(icon = Icons.Outlined.Person) },
        modifier = Modifier.fillMaxWidth().clickable { onPick(item) },
    )
}
