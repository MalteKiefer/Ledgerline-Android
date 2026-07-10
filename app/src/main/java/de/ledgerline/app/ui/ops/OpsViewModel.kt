package de.ledgerline.app.ui.ops

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.ops.OpProgress
import de.ledgerline.app.core.ops.OperationManager
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Exposes the app-wide list of running operations for the shared progress overlay.
 * Reads straight from [OperationManager.active] so any screen can render the current
 * ops (face/duplicate scans, uploads) — including those that survive backgrounding.
 */
@HiltViewModel
class OpsViewModel @Inject constructor(
    operationManager: OperationManager,
) : ViewModel() {
    val active: StateFlow<List<OpProgress>> = operationManager.active
}
