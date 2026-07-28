package de.ledgerline.app.ui.explore

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
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
import de.ledgerline.app.core.map.MapDownloadState
import de.ledgerline.app.core.map.OfflineMapRegion
import de.ledgerline.app.ui.common.AppTopBar
import de.ledgerline.app.ui.common.LedgerRow
import de.ledgerline.app.ui.common.ListBottomPadding
import de.ledgerline.app.ui.common.RowChevron
import de.ledgerline.app.ui.common.SoftIconChip
import de.ledgerline.app.ui.common.listSection
import de.ledgerline.app.ui.theme.Brand
import de.ledgerline.app.ui.workspace.common.SearchField
import de.ledgerline.app.ui.workspace.common.humanSize

/**
 * Offline map catalog: a folder browser (continents → countries → sub-regions, e.g. Europe →
 * Germany → Bundesländer) with a name search across the whole tree. Leaves download / cancel /
 * delete individual `.map` regions.
 */
@Composable
fun OfflineMapsScreen(onBack: () -> Unit, vm: OfflineMapsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val updates by vm.updates.collectAsStateWithLifecycle()
    var path by remember { mutableStateOf(listOf<OfflineMapRegion>()) }
    var query by remember { mutableStateOf("") }

    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Localized display name: countries via ISO code → device language; continents via strings.
    fun nameOf(r: OfflineMapRegion): String =
        r.code?.let { java.util.Locale("", it).getDisplayCountry(locale).ifBlank { r.name } }
            ?: continentRes(r.id)?.let { ctx.getString(it) }
            ?: r.name
    val collator = remember(locale) { java.text.Collator.getInstance(locale).apply { strength = java.text.Collator.PRIMARY } }

    BackHandler { if (path.isNotEmpty()) path = path.dropLast(1) else onBack() }

    val q = query.trim()
    val items: List<OfflineMapRegion> = when {
        q.isNotBlank() -> vm.catalog.leaves().filter {
            it.name.contains(q, true) || nameOf(it).contains(q, true) || (it.path?.substringAfterLast('/')?.contains(q, true) == true)
        }
        path.isEmpty() -> vm.catalog.regions
        else -> path.last().children
    }.sortedWith(Comparator { a, b -> collator.compare(nameOf(a), nameOf(b)) })
    val title = if (q.isNotBlank()) stringResource(R.string.offline_maps_title) else path.lastOrNull()?.let { nameOf(it) } ?: stringResource(R.string.offline_maps_title)

    Scaffold(
        topBar = { AppTopBar(title = title, onBack = { if (path.isNotEmpty()) path = path.dropLast(1) else onBack() }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = ListBottomPadding) {
                item { SearchField(query = query, onQueryChange = { query = it }) }
                listSection(items, key = { it.id }) { region ->
                    if (region.isLeaf) LeafRow(nameOf(region), region, state[region.id] ?: MapDownloadState.NotInstalled, region.id in updates, vm)
                    else LedgerRow(
                        title = nameOf(region),
                        subtitle = null,
                        leading = { SoftIconChip(Icons.Outlined.Folder, tint = Brand.tintBlue) },
                        trailing = { RowChevron() },
                        onClick = { path = path + region },
                    )
                }
            }
        }
    }
}

private fun continentRes(id: String): Int? = when (id) {
    "europe" -> R.string.continent_europe
    "north-america" -> R.string.continent_north_america
    "asia" -> R.string.continent_asia
    "south-america" -> R.string.continent_south_america
    "africa" -> R.string.continent_africa
    "australia-oceania" -> R.string.continent_oceania
    else -> null
}

@Composable
private fun LeafRow(title: String, region: OfflineMapRegion, st: MapDownloadState, updateAvailable: Boolean, vm: OfflineMapsViewModel) {
    LedgerRow(
        title = title,
        subtitle = when (st) {
            is MapDownloadState.Downloading ->
                if (st.totalBytes > 0) "${humanSize(st.receivedBytes)} / ${humanSize(st.totalBytes)} · ${humanSize(st.bytesPerSec)}/s"
                else "${humanSize(st.receivedBytes)} · ${humanSize(st.bytesPerSec)}/s"
            is MapDownloadState.Installed -> if (updateAvailable) stringResource(R.string.offline_maps_update) else humanSize(st.bytes)
            is MapDownloadState.Failed -> stringResource(R.string.offline_maps_failed)
            MapDownloadState.NotInstalled -> if (region.approxSizeMb > 0) "~${region.approxSizeMb} MB" else null
        },
        leading = { SoftIconChip(Icons.Outlined.Map, tint = Brand.tintTeal) },
        trailing = {
            when (st) {
                is MapDownloadState.Downloading -> Box(contentAlignment = Alignment.Center) {
                    if (st.totalBytes > 0) {
                        CircularProgressIndicator(progress = { (st.receivedBytes.toFloat() / st.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.size(38.dp), strokeWidth = 3.dp)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(38.dp), strokeWidth = 3.dp)
                    }
                    IconButton(onClick = { vm.cancel(region.id) }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel), modifier = Modifier.size(18.dp))
                    }
                }
                is MapDownloadState.Installed -> androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    if (updateAvailable) {
                        IconButton(onClick = { vm.download(region) }) {
                            Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.offline_maps_update_action), tint = Brand.accent)
                        }
                    }
                    IconButton(onClick = { vm.delete(region.id) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                }
                else -> IconButton(onClick = { vm.download(region) }) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.offline_maps_download), tint = Brand.accent)
                }
            }
        },
    )
}
