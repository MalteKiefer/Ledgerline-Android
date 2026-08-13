package de.ledgerline.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.R
import de.ledgerline.app.data.AccountRepository
import de.ledgerline.app.data.remote.dto.SearchGroup
import de.ledgerline.app.ui.common.AppScaffold
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.SectionLabel
import de.ledgerline.app.ui.theme.cardSurface
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cross-module global search (`GET /api/v1/search`). Debounced query → grouped results. Only the
 * modules this app surfaces (files, notes, finance) are shown; the rest are dropped. Tapping a hit
 * asks the shell to switch to the owning module (record-level deep-open is a follow-up).
 */
@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val account: AccountRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Result groups filtered to the in-scope modules only. */
    private val _groups = MutableStateFlow<List<SearchGroup>>(emptyList())
    val groups: StateFlow<List<SearchGroup>> = _groups.asStateFlow()

    private val inScope = setOf("files", "notes", "finance")
    private var job: Job? = null

    fun onQuery(q: String) {
        _query.value = q
        job?.cancel()
        if (q.trim().length < 2) { _groups.value = emptyList(); _loading.value = false; return }
        job = viewModelScope.launch {
            delay(300) // debounce
            _loading.value = true
            val res = account.globalSearch(q)
            _groups.value = res?.groups.orEmpty().filter { it.module in inScope && it.items.isNotEmpty() }
            _loading.value = false
        }
    }
}

@Composable
fun GlobalSearchScreen(
    onOpenModule: (String) -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    vm: GlobalSearchViewModel = hiltViewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()

    AppScaffold(topBar = { AppTopBar(title = stringResource(R.string.search_title), onBack = null) }) { pad ->
        Column(
            Modifier.fillMaxSize()
                .padding(top = pad.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.onQuery(it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                label = { Text(stringResource(R.string.search_all_hint)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
            )

            when {
                loading -> CircularProgressIndicator()
                query.trim().length >= 2 && groups.isEmpty() ->
                    Text(stringResource(R.string.files_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.forEach { group ->
                        item(key = "h_${group.module}") { SectionLabel(moduleLabel(group.module)) }
                        items(group.items, key = { "${group.module}_${it.id}" }) { hit ->
                            Column(
                                Modifier.fillMaxWidth().clickable { onOpenModule(group.module) }.cardSurface()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(hit.title.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
                                hit.subtitle?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun moduleLabel(module: String): String = stringResource(
    when (module) {
        "files" -> R.string.tab_files
        "notes" -> R.string.tab_notes
        "finance" -> R.string.tab_finance
        else -> R.string.search_title
    }
)
