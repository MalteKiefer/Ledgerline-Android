package de.ledgerline.app.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.R
import de.ledgerline.app.data.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MapTilesViewModel @Inject constructor(private val settings: SettingsStore) : ViewModel() {
    val enabled: StateFlow<Boolean> = settings.mapTilesEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun enable() {
        viewModelScope.launch { settings.setMapTilesEnabled(true) }
    }
}

/**
 * Privacy gate for map tiles. When map tiles are disabled (the default), [content] — which
 * builds and attaches a MapLibre MapView — is NOT composed, so no tile request derived from
 * the user's private photo coordinates reaches the third-party OSM tile server. Instead a
 * placeholder with an explicit "load map" opt-in is shown (M3).
 */
@Composable
fun MapTilesGate(
    modifier: Modifier = Modifier,
    vm: MapTilesViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    if (enabled) {
        content()
    } else {
        Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Map,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.map_tiles_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                FilledTonalButton(onClick = vm::enable) {
                    Text(stringResource(R.string.map_tiles_enable))
                }
            }
        }
    }
}
