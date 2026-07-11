package de.ledgerline.app.core.ops

import de.ledgerline.app.core.security.VaultLocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class OpKind { FACE_SCAN, DUPLICATE_SCAN, UPLOAD, BLOB_CLEANUP, PREFETCH }

data class OpProgress(val id: Long, val kind: OpKind, val current: Int, val total: Int)

/**
 * Owns an application-scoped coroutine scope so long operations (face/duplicate
 * scans, uploads) survive the Activity going to background. Tracks active
 * operations + progress, drives the foreground [ServiceController], and makes the
 * deferred-wipe decision.
 *
 * Security frame: while an op runs with the background setting on, the Vault Key is
 * deliberately kept in memory past a background event — a user-consented relaxation
 * scoped to *active operations only*, behind a visible notification. When the last
 * op drains while the app is backgrounded (and the setting is on), [VaultLocker.lock]
 * is called immediately to restore the zero-knowledge wipe.
 */
@Singleton
class OperationManager(
    private val setting: BackgroundOpsSetting,
    private val locker: VaultLocker,
    private val serviceController: ServiceController,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
) {
    /** Production constructor — ops run on [Dispatchers.Default]. Tests use the
     *  4-arg constructor to inject a test dispatcher so op coroutines are
     *  deterministic and don't outlive the test (no MainDispatcher teardown race). */
    @Inject constructor(
        setting: BackgroundOpsSetting,
        locker: VaultLocker,
        serviceController: ServiceController,
    ) : this(setting, locker, serviceController, Dispatchers.Default)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _active = MutableStateFlow<List<OpProgress>>(emptyList())

    /** Active operations with live progress — for UI overlays and the service notification. */
    val active: StateFlow<List<OpProgress>> = _active.asStateFlow()

    private val idCounter = AtomicLong(0)

    /** Latest value of the background setting, kept in sync by [scope]. */
    @Volatile
    private var bgEnabled: Boolean = true

    @Volatile
    private var appInBackground = false

    /** Guards [_active] mutations so the 0↔non-0 edge detection stays race-free. */
    private val lock = Any()

    init {
        // Seed synchronously so bgEnabled is correct from the first run() call...
        bgEnabled = runBlocking { setting.enabledFlow.first() }
        // ...then keep it up to date.
        scope.launch { setting.enabledFlow.collect { bgEnabled = it } }
    }

    /**
     * Run [block] as a tracked operation. [block] receives a `report(current,total)`
     * reporter that updates this op's progress in [active]. Runs in the app scope so
     * it survives the Activity being backgrounded. Starts the foreground service when
     * the first op begins (if the setting is on) and stops it when the last op ends.
     * Returns the [Job] so callers can cancel.
     */
    fun run(
        kind: OpKind,
        total: Int = 0,
        block: suspend (report: (Int, Int) -> Unit) -> Unit,
    ): Job {
        val id = idCounter.incrementAndGet()

        val startService: Boolean
        synchronized(lock) {
            val wasEmpty = _active.value.isEmpty()
            _active.value = _active.value + OpProgress(id, kind, 0, total)
            // Edge: list just went 0 -> non-0. Compute inside the lock.
            startService = wasEmpty && bgEnabled
        }
        if (startService) serviceController.start()

        return scope.launch {
            try {
                block { cur, tot -> updateProgress(id, cur, tot) }
            } finally {
                finish(id)
            }
        }
    }

    private fun updateProgress(id: Long, current: Int, total: Int) {
        synchronized(lock) {
            _active.value = _active.value.map {
                if (it.id == id) it.copy(current = current, total = total) else it
            }
        }
    }

    private fun finish(id: Long) {
        val nowEmpty: Boolean
        synchronized(lock) {
            _active.value = _active.value.filterNot { it.id == id }
            // Edge: list just went non-0 -> 0. Compute inside the lock.
            nowEmpty = _active.value.isEmpty()
        }
        if (nowEmpty) {
            serviceController.stop()
            // Deferred wipe: the op ran past a background event; now that it is done
            // and we are still backgrounded (with the setting on), restore the lock.
            if (appInBackground && bgEnabled) locker.lock()
        }
    }

    fun hasActive(): Boolean = _active.value.isNotEmpty()

    fun isBackgroundEnabled(): Boolean = bgEnabled

    /** Called by MainActivity lifecycle when the app is backgrounded. */
    fun onAppBackground() {
        appInBackground = true
    }

    /** Called by MainActivity lifecycle when the app returns to foreground. */
    fun onAppForeground() {
        appInBackground = false
    }
}
