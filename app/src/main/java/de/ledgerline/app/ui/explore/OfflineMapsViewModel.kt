package de.ledgerline.app.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.map.MapDownloadState
import de.ledgerline.app.core.map.OfflineMapCatalog
import de.ledgerline.app.core.map.OfflineMapRegion
import de.ledgerline.app.core.map.OfflineMapStore
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class OfflineMapsViewModel @Inject constructor(
    private val store: OfflineMapStore,
) : ViewModel() {
    val catalog: OfflineMapCatalog get() = store.catalog
    val state: StateFlow<Map<String, MapDownloadState>> = store.state
    val updates: StateFlow<Set<String>> = store.updates

    init {
        store.refreshInstalled()
        viewModelScope.launch { store.checkUpdates() }
    }

    fun download(region: OfflineMapRegion) = store.startDownload(region)
    fun cancel(id: String) = store.cancelDownload(id)
    fun delete(id: String) = store.delete(id)
    fun totalInstalledBytes(): Long = store.totalInstalledBytes()
}
