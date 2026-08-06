package de.ledgerline.app.core.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared "store is degraded" signal: set by the sharded repositories when a shard blob is durably
 * missing (404 after retries), read by the screens to show a non-dismissable "nothing was deleted"
 * banner. While degraded, writes are frozen (see the repos) so the root is never rewritten to drop
 * the missing shard. A holder (not repo injection) keeps the ViewModels easy to construct in tests.
 */
@Singleton
class DegradedState @Inject constructor() {
    private val _files = MutableStateFlow(false)
    val files: StateFlow<Boolean> = _files

    fun setFiles(degraded: Boolean) { _files.value = degraded }
}
