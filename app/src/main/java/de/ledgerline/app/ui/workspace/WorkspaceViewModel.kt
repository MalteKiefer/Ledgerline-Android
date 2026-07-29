package de.ledgerline.app.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.core.backup.GalleryBackupManager
import de.ledgerline.app.core.offline.Prefetcher
import de.ledgerline.app.domain.usecase.LoadWorkspace
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Loads the workspace once when the workspace UI (re-)enters composition if the
 *  shared cache is empty. After a lock the cache is cleared, so re-entering HOME
 *  triggers a fresh authenticated fetch. */
@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val load: LoadWorkspace,
    private val cache: WorkspaceCache,
    private val prefetcher: Prefetcher,
    private val backupManager: GalleryBackupManager,
    moduleAccess: de.ledgerline.app.core.ModuleAccess,
    private val reachability: de.ledgerline.app.core.ServerReachability,
) : ViewModel() {

    /** The account's allowed module keys (`/me` rights model); null = unknown → allow all. */
    val allowedModules = moduleAccess.allowed

    /** Whether the self-hosted server is reachable (GET /up). false → the app is in offline mode. */
    val serverOnline = reachability.online

    init {
        // When the server comes back after being offline, catch up: reload + re-prefetch.
        viewModelScope.launch {
            var prev = reachability.online.value
            reachability.online.collect { now ->
                if (now && !prev) { load.invoke(); prefetcher.maybePrefetchOnUnlock() }
                prev = now
            }
        }
    }
    /**
     * Loads the workspace if the cache is empty, then kicks off auto-prefetch on unlock.
     * [Prefetcher] self-gates on policy + constraints + no-stacking and enumerates
     * whatever caches are populated, so calling it once here after the load is safe.
     */
    fun ensureLoaded() {
        viewModelScope.launch {
            reachability.checkNow()   // probe /up immediately on open (post-unlock), not up to 60s later
            if (cache.value.value == null) load.invoke()
            prefetcher.maybePrefetchOnUnlock()
            backupManager.maybeRun()
        }
    }
}
