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
) : ViewModel() {

    /** The account's allowed module keys (`/me` rights model); null = unknown → allow all. */
    val allowedModules = moduleAccess.allowed
    /**
     * Loads the workspace if the cache is empty, then kicks off auto-prefetch on unlock.
     * [Prefetcher] self-gates on policy + constraints + no-stacking and enumerates
     * whatever caches are populated, so calling it once here after the load is safe.
     */
    fun ensureLoaded() {
        viewModelScope.launch {
            if (cache.value.value == null) load.invoke()
            prefetcher.maybePrefetchOnUnlock()
            backupManager.maybeRun()
        }
    }
}
