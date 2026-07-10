package de.ledgerline.app.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.WorkspaceCache
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
) : ViewModel() {
    fun ensureLoaded() {
        if (cache.value.value == null) viewModelScope.launch { load.invoke() }
    }
}
